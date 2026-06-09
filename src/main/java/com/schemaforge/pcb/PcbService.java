package com.schemaforge.pcb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class PcbService {

    private static final Logger log = LoggerFactory.getLogger(PcbService.class);

    private final WebClient openAiClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path outputsDir;

    public PcbService(WebClient openAiClient) throws IOException {
        this.openAiClient = openAiClient;
        this.outputsDir = Path.of("outputs");
        Files.createDirectories(outputsDir);
    }

    // ── GPT로 .kicad_pcb 생성 ──────────────────────────────────────

    public Map<String, Object> generateFromNetlist(String netFilename) throws Exception {
        String safe = Path.of(netFilename).getFileName().toString();
        Path netPath = outputsDir.resolve(safe);
        if (!Files.exists(netPath)) throw new RuntimeException("Netlist file not found: " + safe);
        String netContent = Files.readString(netPath);
        String baseName = safe.replaceFirst("\\.net$", "");
        String kicadPcb = callGptForPcb(netContent, null, baseName);
        String pcbFilename = baseName + ".kicad_pcb";
        Files.writeString(outputsDir.resolve(pcbFilename), kicadPcb);
        return Map.of("pcbFilename", pcbFilename);
    }

    public Map<String, Object> generateFromGraph(JsonNode graph, String baseName) throws Exception {
        String graphJson = mapper.writeValueAsString(graph);
        String kicadPcb = callGptForPcb(null, graphJson, baseName);
        String pcbFilename = baseName + ".kicad_pcb";
        Files.writeString(outputsDir.resolve(pcbFilename), kicadPcb);
        return Map.of("pcbFilename", pcbFilename);
    }

    private String callGptForPcb(String netContent, String graphJson, String baseName) {
        String circuitInfo = netContent != null ? netContent : graphJson;

        String systemPrompt = """
            You are a KiCad PCB layout expert. Generate a complete, valid KiCad 7 (.kicad_pcb) file.

            Rules:
            - Output ONLY the raw .kicad_pcb file content. No explanations, no markdown.
            - Start exactly with: (kicad_pcb
            - Use KiCad version 20221018 format
            - Place components on F.Cu layer in a clean grid layout
            - Route copper traces on F.Cu following the netlist connections
            - Use SMD footprints: 0402 for R/C/L, SOT-23 for Q/transistors, SOP-8 or DIP for U/ICs
            - Add board outline on Edge.Cuts layer (board size based on component count)
            - Add reference labels on F.SilkS layer
            - Include F.Mask and B.Mask layers
            - Ensure all component references and values match the input exactly
            """;

        String userMsg = "Generate a .kicad_pcb file for this circuit:\n\n" + circuitInfo;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user",   "content", userMsg)
        ));
        body.put("temperature", 0.1);
        body.put("max_tokens", 4000);

        String raw = openAiClient.post()
            .uri("/v1/chat/completions")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .map(json -> json.path("choices").get(0).path("message").path("content").asText(""))
            .block();

        // Strip markdown fences if present
        if (raw != null && raw.contains("```")) {
            raw = raw.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").trim();
        }
        return raw != null ? raw : "";
    }

    // ── Gerber 생성 ────────────────────────────────────────────────

    public Map<String, Object> generateGerbers(String pcbFilename) throws Exception {
        String safe = Path.of(pcbFilename).getFileName().toString();
        Path pcbPath = outputsDir.resolve(safe);
        if (!Files.exists(pcbPath)) throw new RuntimeException("PCB file not found: " + safe);

        String baseName = safe.replaceFirst("\\.kicad_pcb$", "");
        Path gerberDir = outputsDir.resolve(baseName + "_gerbers");
        Files.createDirectories(gerberDir);

        // Run Python gerber generator script
        Path scriptPath = findGerberScript();
        ProcessBuilder pb = new ProcessBuilder(
            "python", scriptPath.toAbsolutePath().toString(),
            pcbPath.toAbsolutePath().toString(),
            gerberDir.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(false);
        Process proc = pb.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(proc.getInputStream()));
             BufferedReader err = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
            out.lines().forEach(l -> stdout.append(l).append("\n"));
            err.lines().forEach(l -> stderr.append(l).append("\n"));
        }

        boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
        if (!finished) { proc.destroyForcibly(); throw new RuntimeException("Gerber generation timed out"); }
        if (proc.exitValue() != 0) {
            log.error("Gerber script failed: {}", stderr);
            throw new RuntimeException("Gerber generation failed: " + stderr.toString().trim());
        }

        List<String> files = Files.list(gerberDir)
            .map(p -> p.getFileName().toString())
            .sorted()
            .toList();

        return Map.of(
            "gerberDir", gerberDir.toString(),
            "files", files
        );
    }

    private Path findGerberScript() throws IOException {
        // Look for the script next to the jar or in working directory
        Path[] candidates = {
            Path.of("scripts", "generate_gerbers.py"),
            Path.of("generate_gerbers.py"),
        };
        for (Path c : candidates) {
            if (Files.exists(c)) return c;
        }
        // Extract from classpath as fallback
        Path tmp = Files.createTempFile("generate_gerbers_", ".py");
        try (InputStream is = getClass().getResourceAsStream("/scripts/generate_gerbers.py")) {
            if (is != null) { Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING); return tmp; }
        }
        throw new IOException("generate_gerbers.py not found");
    }
}
