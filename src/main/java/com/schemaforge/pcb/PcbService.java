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

/**
 * PCB 레이아웃 및 거버 파일 생성 서비스
 *
 * 이 서비스가 하는 일:
 *   1. GPT-4o를 호출해 KiCad PCB 파일(.kicad_pcb) 생성
 *   2. Python 스크립트를 실행해 거버(Gerber) 제조 파일 생성
 *
 * .kicad_pcb 파일이란?
 *   KiCad EDA 소프트웨어의 PCB 레이아웃 파일 형식.
 *   S-expression(괄호 중첩 구조) 텍스트 형식으로, KiCad에서 바로 열 수 있음.
 *   부품 배치, 동선 라우팅, 보드 외형, 실크스크린 레이블 등을 포함.
 *
 * 거버(Gerber, RS-274X) 파일이란?
 *   PCB 제조 공장에 전달하는 표준 제조 도면 파일.
 *   각 레이어를 별도 파일로 분리:
 *     - F.Cu.gbr    : 앞면 동박(구리) 패턴 — 회로 트레이스
 *     - F.Mask.gbr  : 솔더 마스크 — 납땜할 패드 부위만 구리 노출
 *     - Edge.Cuts.gbr : 보드 외형선 — 공장에서 이 선을 따라 절단
 *     - .drl        : Excellon 드릴 파일 — 구멍(스루홀, 비아) 위치
 */
@Service
public class PcbService {

    private static final Logger log = LoggerFactory.getLogger(PcbService.class);

    /** OpenAI API 호출용 HTTP 클라이언트 (AppConfig에서 주입) */
    private final WebClient openAiClient;

    /** Java 객체 ↔ JSON 변환 라이브러리 */
    private final ObjectMapper mapper = new ObjectMapper();

    /** 생성된 .kicad_pcb, 거버 파일이 저장되는 디렉토리 */
    private final Path outputsDir;

    /**
     * 생성자: Spring이 AppConfig의 WebClient 빈을 자동 주입.
     * outputs/ 디렉토리가 없으면 자동 생성.
     */
    public PcbService(WebClient openAiClient) throws IOException {
        this.openAiClient = openAiClient;
        this.outputsDir = Path.of("outputs");
        Files.createDirectories(outputsDir);
    }

    // ════════════════════════════════════════════════════════════════
    // .kicad_pcb 파일 생성 — GPT-4o 호출
    // ════════════════════════════════════════════════════════════════

    /**
     * 넷리스트(.net) 파일로부터 KiCad PCB 파일 생성
     *
     * 처리 순서:
     *   1. outputs/ 폴더에서 .net 파일 읽기
     *   2. GPT-4o에 파일 내용 전달 → .kicad_pcb 텍스트 생성
     *   3. 같은 이름으로 .kicad_pcb 파일 저장
     *
     * @param netFilename 넷리스트 파일명 (예: abc123.net)
     * @return { "pcbFilename": "abc123.kicad_pcb" }
     */
    public Map<String, Object> generateFromNetlist(String netFilename) throws Exception {
        // 경로 조작 방지: 파일명만 추출
        String safe = Path.of(netFilename).getFileName().toString();
        Path netPath = outputsDir.resolve(safe);
        if (!Files.exists(netPath)) throw new RuntimeException("Netlist file not found: " + safe);

        String netContent = Files.readString(netPath);
        String baseName = safe.replaceFirst("\\.net$", "");

        // GPT-4o에 넷리스트 내용 전달 → KiCad PCB 파일 텍스트 생성
        String kicadPcb = callGptForPcb(netContent, null, baseName);

        String pcbFilename = baseName + ".kicad_pcb";
        Files.writeString(outputsDir.resolve(pcbFilename), kicadPcb);
        return Map.of("pcbFilename", pcbFilename);
    }

    /**
     * 수정된 회로 그래프(NetGraph JSON)로부터 KiCad PCB 파일 생성
     *
     * AI 채팅으로 회로를 수정한 경우 .net 파일 대신 그래프 데이터를 직접 사용.
     * 그래프는 { components: [{ref, value}], nets: [{name, nodes}] } 형태.
     *
     * @param graph    회로 그래프 JSON (부품 목록 + 넷 연결 정보)
     * @param baseName 저장할 파일 기본 이름 (예: "circuit" → "circuit.kicad_pcb")
     * @return { "pcbFilename": "circuit.kicad_pcb" }
     */
    public Map<String, Object> generateFromGraph(JsonNode graph, String baseName) throws Exception {
        // 그래프 객체를 JSON 문자열로 직렬화해서 GPT 프롬프트에 포함
        String graphJson = mapper.writeValueAsString(graph);
        String kicadPcb = callGptForPcb(null, graphJson, baseName);
        String pcbFilename = baseName + ".kicad_pcb";
        Files.writeString(outputsDir.resolve(pcbFilename), kicadPcb);
        return Map.of("pcbFilename", pcbFilename);
    }

    /**
     * GPT-4o를 호출해 KiCad PCB 파일 텍스트 생성
     *
     * GPT 시스템 프롬프트에서 지시하는 내용:
     *   - KiCad 7 형식(version 20221018)으로 출력
     *   - 수동 소자(R/C/L): 0402 SMD 풋프린트
     *   - 트랜지스터(Q): SOT-23 풋프린트
     *   - IC(U): DIP 또는 SOP-8 풋프린트
     *   - F.Cu 레이어에 부품 배치 + 동선 라우팅
     *   - Edge.Cuts 레이어에 보드 외형
     *   - F.SilkS 레이어에 레퍼런스 레이블
     *
     * @param netContent  .net 파일 내용 (넷리스트 경로일 때 사용)
     * @param graphJson   그래프 JSON 문자열 (그래프 경로일 때 사용)
     * @param baseName    파일 기본 이름 (로깅/식별용)
     * @return 생성된 .kicad_pcb 파일 내용 (순수 텍스트)
     */
    private String callGptForPcb(String netContent, String graphJson, String baseName) {
        // netContent와 graphJson 중 실제 값이 있는 것을 GPT에 전달
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

        // OpenAI Chat Completions API 호출
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user",   "content", userMsg)
        ));
        body.put("temperature", 0.1); // 낮은 temperature → 일관된 코드 형식 출력
        body.put("max_tokens", 4000); // KiCad 파일은 길어서 충분히 설정

        String raw = openAiClient.post()
            .uri("/v1/chat/completions")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(com.fasterxml.jackson.databind.JsonNode.class)
            .map(json -> json.path("choices").get(0).path("message").path("content").asText(""))
            .block();

        // GPT가 가끔 ```kicad ... ``` 형태로 감싸서 반환할 때 마크다운 제거
        if (raw != null && raw.contains("```")) {
            raw = raw.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").trim();
        }
        return raw != null ? raw : "";
    }

    // ════════════════════════════════════════════════════════════════
    // 거버 파일 생성 — Python 스크립트 실행
    // ════════════════════════════════════════════════════════════════

    /**
     * .kicad_pcb 파일로부터 RS-274X 거버 파일 생성
     *
     * Java에서 Python 프로세스(generate_gerbers.py)를 직접 실행함.
     * Python 스크립트가 하는 일:
     *   1. .kicad_pcb 파일을 S-expression 형식으로 파싱
     *   2. 부품 풋프린트(패드 위치/크기), 동선(segment), 보드 외형(Edge.Cuts) 추출
     *   3. RS-274X 형식으로 4개 거버 파일 생성
     *   4. Excellon 형식으로 드릴 파일 생성
     *
     * ProcessBuilder:
     *   Java에서 외부 프로세스(여기서는 Python)를 실행하는 클래스.
     *   stdout/stderr를 분리해서 오류 메시지를 정확히 파악할 수 있음.
     *
     * proc.waitFor(60, SECONDS):
     *   거버 생성은 단순 파싱이라 60초면 충분.
     *   타임아웃 시 강제 종료 후 예외 발생.
     *
     * @param pcbFilename KiCad PCB 파일명 (예: abc123.kicad_pcb)
     * @return { "gerberDir": "outputs/abc123_gerbers", "files": [...] }
     */
    public Map<String, Object> generateGerbers(String pcbFilename) throws Exception {
        String safe = Path.of(pcbFilename).getFileName().toString();
        Path pcbPath = outputsDir.resolve(safe);
        if (!Files.exists(pcbPath)) throw new RuntimeException("PCB file not found: " + safe);

        // 거버 파일 저장 디렉토리: outputs/{baseName}_gerbers/
        String baseName = safe.replaceFirst("\\.kicad_pcb$", "");
        Path gerberDir = outputsDir.resolve(baseName + "_gerbers");
        Files.createDirectories(gerberDir);

        // Python 스크립트 경로 탐색
        Path scriptPath = findGerberScript();

        // Python 프로세스 실행: python generate_gerbers.py <pcb파일> <출력폴더>
        ProcessBuilder pb = new ProcessBuilder(
            "python", scriptPath.toAbsolutePath().toString(),
            pcbPath.toAbsolutePath().toString(),
            gerberDir.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(false); // stdout과 stderr 분리 (오류 파악용)
        Process proc = pb.start();

        // stdout/stderr 비동기 수집 (proc.waitFor 전에 읽지 않으면 버퍼 블로킹 발생 가능)
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(proc.getInputStream()));
             BufferedReader err = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
            out.lines().forEach(l -> stdout.append(l).append("\n"));
            err.lines().forEach(l -> stderr.append(l).append("\n"));
        }

        boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new RuntimeException("Gerber generation timed out");
        }
        if (proc.exitValue() != 0) {
            log.error("Gerber script failed: {}", stderr);
            throw new RuntimeException("Gerber generation failed: " + stderr.toString().trim());
        }

        // 생성된 파일 목록 수집 (이름순 정렬)
        List<String> files = Files.list(gerberDir)
            .map(p -> p.getFileName().toString())
            .sorted()
            .toList();

        return Map.of(
            "gerberDir", gerberDir.toString(),
            "files", files
        );
    }

    /**
     * generate_gerbers.py 스크립트 경로 탐색
     *
     * 탐색 순서:
     *   1. scripts/generate_gerbers.py (Docker 이미지 내 배치 경로)
     *   2. generate_gerbers.py (작업 디렉토리)
     *   3. 클래스패스 /scripts/generate_gerbers.py (JAR 내부 → 임시 파일로 추출)
     *
     * 클래스패스 폴백:
     *   JAR 내부에 번들된 Python 스크립트를 임시 파일로 추출해서 실행.
     *   Docker 이미지에 scripts/ 폴더가 있으면 이 경로는 사용되지 않음.
     */
    private Path findGerberScript() throws IOException {
        Path[] candidates = {
            Path.of("scripts", "generate_gerbers.py"),
            Path.of("generate_gerbers.py"),
        };
        for (Path c : candidates) {
            if (Files.exists(c)) return c;
        }
        // 클래스패스에서 추출 (JAR 번들용 폴백)
        Path tmp = Files.createTempFile("generate_gerbers_", ".py");
        try (InputStream is = getClass().getResourceAsStream("/scripts/generate_gerbers.py")) {
            if (is != null) {
                Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
                return tmp;
            }
        }
        throw new IOException("generate_gerbers.py not found in scripts/ or classpath");
    }
}
