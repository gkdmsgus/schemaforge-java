package com.schemaforge.pcb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaforge.auth.AuthService;
import com.schemaforge.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.*;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class PcbController {

    private final PcbService pcbService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path outputsDir = Path.of("outputs");

    public PcbController(PcbService pcbService) {
        this.pcbService = pcbService;
    }

    @PostMapping("/generate_pcb")
    public ResponseEntity<?> generatePcb(@RequestBody Map<String, String> body,
                                          HttpServletRequest req) {
        AuthUser user = (AuthUser) req.getAttribute("authUser");
        if (user == null) return unauthorized();
        try {
            String filename = body.get("filename");
            if (filename == null || filename.isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "filename required"));
            return ResponseEntity.ok(pcbService.generateFromNetlist(filename));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/generate_pcb_from_graph")
    public ResponseEntity<?> generatePcbFromGraph(@RequestBody Map<String, Object> body,
                                                    HttpServletRequest req) {
        AuthUser user = (AuthUser) req.getAttribute("authUser");
        if (user == null) return unauthorized();
        try {
            String baseName = (String) body.getOrDefault("baseName", "circuit");
            Object graphObj = body.get("graph");
            if (graphObj == null)
                return ResponseEntity.badRequest().body(Map.of("error", "graph required"));
            JsonNode graph = mapper.valueToTree(graphObj);
            return ResponseEntity.ok(pcbService.generateFromGraph(graph, baseName));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/generate_gerber")
    public ResponseEntity<?> generateGerber(@RequestBody Map<String, String> body,
                                              HttpServletRequest req) {
        AuthUser user = (AuthUser) req.getAttribute("authUser");
        if (user == null) return unauthorized();
        try {
            String pcbFilename = body.get("pcbFilename");
            if (pcbFilename == null || pcbFilename.isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "pcbFilename required"));
            return ResponseEntity.ok(pcbService.generateGerbers(pcbFilename));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/download_pcb/{filename}")
    public ResponseEntity<?> downloadPcb(@PathVariable String filename) {
        String safe = Path.of(filename).getFileName().toString();
        Path path = outputsDir.resolve(safe);
        if (!Files.exists(path)) return ResponseEntity.notFound().build();
        try {
            byte[] bytes = Files.readAllBytes(path);
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + safe + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다."));
    }
}
