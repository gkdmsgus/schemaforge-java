package com.schemaforge.pcb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaforge.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.*;
import java.util.Map;

/**
 * PCB 및 거버 파일 생성 컨트롤러
 *
 * 회로 생성(/generate)이 끝난 후 사용자가 PCB 레이아웃을 원할 때 호출되는 엔드포인트들.
 *
 * 전체 흐름:
 *   1. /generate_pcb 또는 /generate_pcb_from_graph
 *      → GPT-4o가 KiCad 7 형식의 .kicad_pcb 파일 생성
 *      → KiCad에서 바로 열 수 있는 PCB 레이아웃 파일
 *
 *   2. /generate_gerber
 *      → Python 스크립트(generate_gerbers.py)가 .kicad_pcb를 파싱
 *      → RS-274X 형식의 거버 파일 4종 생성 (PCB 제조 공장 전달용)
 *
 *   3. /download_pcb/{filename}
 *      → 생성된 .kicad_pcb 파일을 브라우저로 다운로드
 *
 * 거버(Gerber) 파일이란?
 *   PCB 제조 공장(예: JLCPCB, PCBWay)에 보내는 제조 도면 파일.
 *   각 레이어(앞면 동박, 솔더 마스크, 보드 외형, 드릴 위치)를 별도 파일로 전달함.
 *   이 파일들만 있으면 실제 PCB를 공장에서 제작할 수 있음.
 *
 * 보안:
 *   모든 POST 엔드포인트는 AuthFilter에서 로그인 여부를 검증한 뒤 진입.
 *   컨트롤러에서도 authUser null 체크로 이중 방어.
 */
@RestController
@CrossOrigin(origins = "*")
public class PcbController {

    private final PcbService pcbService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** outputs/ 폴더: 생성된 .kicad_pcb, 거버 파일이 저장되는 디렉토리 */
    private final Path outputsDir = Path.of("outputs");

    public PcbController(PcbService pcbService) {
        this.pcbService = pcbService;
    }

    /**
     * POST /generate_pcb — 넷리스트(.net) 파일로부터 PCB 파일 생성
     *
     * 사용 시점: 회로 생성 후 AI 편집 없이 원본 넷리스트로 PCB를 만들 때.
     *
     * 요청: { "filename": "abc123.net" }
     * 응답: { "pcbFilename": "abc123.kicad_pcb" }
     *
     * @param body    요청 바디 — filename 키로 .net 파일명 전달
     * @param req     HTTP 요청 객체 — AuthFilter가 첨부한 authUser 속성 접근용
     */
    @PostMapping("/generate_pcb")
    public ResponseEntity<?> generatePcb(@RequestBody Map<String, String> body,
                                          HttpServletRequest req) {
        // AuthFilter가 설정한 authUser 속성 확인 (로그인 여부 이중 검증)
        AuthUser user = (AuthUser) req.getAttribute("authUser");
        if (user == null) return unauthorized();
        try {
            String filename = body.get("filename");
            if (filename == null || filename.isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "filename required"));
            return ResponseEntity.ok(pcbService.generateFromNetlist(filename));
        } catch (Exception e) {
            // 오류 발생 시 200 OK + error 필드로 반환 (프론트엔드 통일 처리)
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /generate_pcb_from_graph — 수정된 회로 그래프로부터 PCB 파일 생성
     *
     * 사용 시점: AI 채팅으로 회로를 수정한 경우.
     *   수정된 그래프(컴포넌트 + 넷 정보)를 직접 전달하면
     *   GPT가 이를 기반으로 .kicad_pcb 파일을 생성함.
     *
     * 요청: { "graph": { components: [...], nets: [...] }, "baseName": "circuit" }
     * 응답: { "pcbFilename": "circuit.kicad_pcb" }
     *
     * @param body    graph 객체(NetGraph JSON)와 baseName(파일명 기반) 포함
     */
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
            // Map → JsonNode 변환 (GPT에 전달하기 위해 JSON 직렬화 필요)
            JsonNode graph = mapper.valueToTree(graphObj);
            return ResponseEntity.ok(pcbService.generateFromGraph(graph, baseName));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /generate_gerber — PCB 파일로부터 거버 제조 파일 생성
     *
     * 사용 시점: PCB 파일이 준비된 후 실제 제조 공장에 보낼 파일이 필요할 때.
     *
     * 내부 동작:
     *   Java → Python 프로세스(generate_gerbers.py) 실행
     *   Python이 .kicad_pcb를 파싱 → 4종 거버 파일 생성:
     *     - {name}-F.Cu.gbr    : 앞면 동박층 (회로 트레이스)
     *     - {name}-F.Mask.gbr  : 솔더 마스크 (납땜 부위 노출)
     *     - {name}-Edge.Cuts.gbr : 보드 외형선
     *     - {name}.drl         : 드릴 파일 (구멍 위치)
     *
     * 요청: { "pcbFilename": "abc123.kicad_pcb" }
     * 응답: { "gerberDir": "outputs/abc123_gerbers", "files": ["F.Cu.gbr", ...] }
     */
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

    /**
     * GET /download_pcb/{filename} — 생성된 .kicad_pcb 파일 다운로드
     *
     * 브라우저가 이 URL을 호출하면 파일이 직접 다운로드됨.
     *
     * 보안 처리:
     *   Path.of(filename).getFileName() — 경로 조작 공격 방지.
     *   예: "../../etc/passwd" 같은 입력을 받아도 파일명만 추출해서
     *   outputs/ 폴더 밖의 파일에 절대 접근하지 못하게 막음.
     *
     * Content-Disposition: attachment
     *   브라우저가 파일을 화면에 표시하지 않고 바로 다운로드하도록 지시.
     *
     * @param filename URL 경로의 파일명 (예: abc123.kicad_pcb)
     */
    @GetMapping("/download_pcb/{filename}")
    public ResponseEntity<?> downloadPcb(@PathVariable String filename) {
        // 경로 조작 방지: 순수 파일명만 추출
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

    /** 인증 실패 시 공통 401 응답 */
    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다."));
    }
}
