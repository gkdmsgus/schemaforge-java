package com.schemaforge.generate;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * /generate, /download, /test_code 엔드포인트 컨트롤러
 *
 * 이 컨트롤러의 핵심 개념은 SSE(Server-Sent Events):
 *
 * 일반 HTTP 통신은 요청 1번 → 응답 1번으로 끝남.
 * 하지만 회로 생성은 GPT 호출(~20초) + Python 실행(~10초)으로
 * 시간이 오래 걸리기 때문에, 결과가 나올 때까지 기다리면 사용자 경험이 나쁨.
 *
 * SSE를 사용하면:
 *   연결을 유지한 채로 서버가 클라이언트에게 여러 번 메시지를 보낼 수 있음.
 *   "분석 중..." → "코드 생성 중..." → "넷리스트 만드는 중..." → "완료!"
 *   이런 식으로 진행 상황을 실시간으로 전달함.
 *
 * 응답 형식 (텍스트):
 *   event: status
 *   data: 🔍 Analysing...
 *
 *   event: done
 *   data: {"code":"...", "graph":{...}}
 */
@RestController
@CrossOrigin(origins = "*")
public class GenerateController {

    private final GenerateService generateService;

    /**
     * Java 21 Virtual Thread 기반 스레드 풀
     *
     * /generate는 GPT 호출과 Python 실행 때문에 수십 초가 걸림.
     * 이 작업을 메인 스레드에서 직접 하면, 그동안 다른 사용자 요청을 처리하지 못함.
     *
     * 해결책: 요청마다 별도의 스레드를 만들어서 작업을 위임하고,
     *         메인 스레드는 바로 다음 요청을 받을 수 있게 함.
     *
     * Virtual Thread(Java 21 신기능):
     *   기존 OS 스레드는 메모리를 많이 써서 수백 개가 한계였음.
     *   Virtual Thread는 JVM이 관리하는 초경량 스레드로,
     *   수만 개를 동시에 만들어도 부담이 없음.
     *   동시 접속자가 많아져도 서버가 안정적으로 처리 가능.
     */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public GenerateController(GenerateService generateService) {
        this.generateService = generateService;
    }

    /**
     * POST /generate — SSE 스트리밍으로 회로 생성
     *
     * produces = MediaType.TEXT_EVENT_STREAM_VALUE
     *   응답의 Content-Type을 "text/event-stream"으로 설정.
     *   브라우저는 이 헤더를 보고 "SSE 연결이구나"라고 인식하고
     *   EventSource API로 이벤트를 순서대로 수신함.
     *
     * 반환 타입 SseEmitter:
     *   SSE 연결을 나타내는 객체.
     *   이 객체를 반환하는 순간 HTTP 응답 헤더가 클라이언트에 전송되고 연결이 열림.
     *   이후 백그라운드 스레드(Virtual Thread)가 이 객체를 통해 이벤트를 계속 보냄.
     *   emitter.complete() 호출 시 연결이 정상 종료됨.
     *
     * 처리 흐름:
     *   1. SseEmitter 생성 (연결 오픈, 타임아웃 120초)
     *   2. Virtual Thread에 generateService.generate() 위임
     *   3. 즉시 emitter 반환 → 메인 스레드는 다음 요청 처리 가능
     *   4. Virtual Thread가 백그라운드에서 GPT 호출, Python 실행 후 이벤트 전송
     */
    @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generate(@RequestBody GenerateRequest request) {
        String description = request.getDescription();

        // 빈 입력 처리: SSE 연결을 열고 error 이벤트만 보낸 뒤 즉시 닫음
        if (description == null || description.isBlank()) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("{\"message\":\"description required\"}"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        // 타임아웃 120초: GPT 응답 대기 + Python 실행 시간을 충분히 커버
        SseEmitter emitter = new SseEmitter(120_000L);

        // Virtual Thread에서 비동기 실행
        // 이 줄 다음 return까지 거의 즉시 실행됨
        executor.submit(() -> generateService.generate(description.trim(), emitter));

        // HTTP 응답 헤더 전송 + 연결 유지 (이후 이벤트는 Virtual Thread가 전송)
        return emitter;
    }

    /**
     * GET /download/{filename} — 생성된 KiCad .net 파일 다운로드
     *
     * @PathVariable
     *   URL 경로의 {filename} 부분을 메서드 파라미터로 받음.
     *   예: /download/abc123.net 요청 시 filename = "abc123.net"
     *
     * Path.getFileName()
     *   보안 처리: 사용자가 "../../etc/passwd" 같은 경로 조작 문자를 입력해도
     *   파일명만 추출해서 outputs/ 폴더 밖의 파일에 접근하지 못하게 막음.
     *
     * Content-Disposition: attachment
     *   브라우저가 파일을 화면에 표시하지 않고 바로 다운로드하도록 지시하는 헤더.
     */
    @GetMapping("/download/{filename}")
    public ResponseEntity<?> download(@PathVariable String filename) {
        String safe = java.nio.file.Path.of(filename).getFileName().toString();
        java.nio.file.Path path = java.nio.file.Path.of("outputs", safe);

        if (!java.nio.file.Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(path);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"schematic.net\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /test_code — 사용자가 직접 수정한 skidl 코드를 다시 실행
     *
     * 프론트엔드 ResultPanel의 코드 편집기에서 skidl 코드를 수정한 뒤
     * "실행" 버튼을 누르면 이 엔드포인트가 호출됨.
     *
     * GenerateService의 runSkidlPublic, parseNetlistPublic을 재사용해서
     * 새 결과를 반환. /generate와 달리 SSE 없이 단순 JSON으로 응답.
     *
     * @RequestBody Map<String, String>
     *   요청 형식이 단순해서 별도 DTO 클래스 없이 Map으로 바로 받음.
     *   예: { "code": "from skidl import *\n..." }
     */
    @PostMapping("/test_code")
    public ResponseEntity<?> testCode(@RequestBody Map<String, String> body) {
        String code = body.getOrDefault("code", "").trim();

        if (code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No code provided."));
        }

        try {
            String outputPath = generateService.runSkidlPublic(code);
            String filename   = java.nio.file.Path.of(outputPath).getFileName().toString();
            String content    = java.nio.file.Files.readString(java.nio.file.Path.of(outputPath));
            var graph         = generateService.parseNetlistPublic(content);

            return ResponseEntity.ok(Map.of("filename", filename, "graph", graph));
        } catch (Exception e) {
            // Python 실행 오류 시 에러 메시지 반환 (프론트에서 빨간색으로 표시)
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }
}
