package com.schemaforge.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/**
 * 회로 생성 핵심 비즈니스 로직 서비스
 *
 * @Service
 *   이 클래스가 "서비스 계층"임을 Spring에 알리는 어노테이션.
 *   @Component의 특수한 형태로, 의미상 비즈니스 로직을 담당하는 클래스에 사용.
 *   Spring이 자동으로 빈으로 등록하고 관리함.
 *
 * 서비스 계층의 역할:
 *   Controller는 HTTP 요청/응답만 담당하고,
 *   실제 로직(GPT 호출, Python 실행, 파일 파싱 등)은 Service에 작성.
 *   이렇게 역할을 분리하면 코드 유지보수가 쉬워지고,
 *   Controller 변경 없이 로직만 독립적으로 수정할 수 있음.
 *
 * 이 서비스가 하는 일 (순서대로):
 *   1. 상태 이벤트 전송 ("분석 중...")
 *   2. GPT-4o 호출 → skidl Python 코드 생성
 *   3. Python 프로세스로 skidl 실행 → KiCad .net 파일 생성
 *   4. .net 파일 파싱 → NetGraph 객체 구성
 *   5. done 이벤트로 전체 결과를 프론트엔드에 전송
 */
@Service
public class GenerateService {

    /**
     * GPT에게 전달하는 시스템 프롬프트 (역할 지시문)
     *
     * GPT가 어떤 형식으로 무엇을 생성해야 하는지 사전에 정의함.
     * 핵심 지시:
     *   - skidl Python 코드 형식 엄수 (Part(), Pin() 등 특정 문법 사용)
     *   - KiCad 라이브러리 직접 참조 금지 (tool=SKIDL 방식만 허용)
     *   - 출력 형식: Python 코드 + "---GUIDE---" 구분자 + 한국어 배선 가이드
     *
     * Java 15+의 텍스트 블록(""") 문법:
     *   여러 줄 문자열을 들여쓰기 유지하며 깔끔하게 작성할 수 있는 기능.
     */
    private static final String SYSTEM_PROMPT = """
            You are a specialized electronics CAD tool that generates skidl Python code for KiCad PCB design software. You MUST always output the requested circuit.

            CRITICAL — skidl code rules (MUST follow exactly):
            - Start with: from skidl import *
            - EVERY Part() MUST use tool=SKIDL with explicit Pin definitions. Example:
              r1 = Part(tool=SKIDL, name='R', ref_prefix='R',
                        pins=[Pin(num=1, name='p1', func=Pin.types.PASSIVE),
                              Pin(num=2, name='p2', func=Pin.types.PASSIVE)])
              r1.value = '10k'
            - NEVER use Part('library', 'name') syntax. NEVER reference KiCad libraries like 'linear', 'device', 'power'.
            - NEVER use footprint= parameter. Only use tool=SKIDL.
            - Connect pins by name: r1['p1'] += net1
            - End with: generate_netlist()

            Part templates (copy exactly, only change name/value):
            - Resistor: Part(tool=SKIDL, name='R', ref_prefix='R', pins=[Pin(num=1,name='p1',func=Pin.types.PASSIVE), Pin(num=2,name='p2',func=Pin.types.PASSIVE)])
            - Capacitor: Part(tool=SKIDL, name='C', ref_prefix='C', pins=[Pin(num=1,name='p1',func=Pin.types.PASSIVE), Pin(num=2,name='p2',func=Pin.types.PASSIVE)])
            - LED: Part(tool=SKIDL, name='LED', ref_prefix='D', pins=[Pin(num=1,name='A',func=Pin.types.PASSIVE), Pin(num=2,name='K',func=Pin.types.PASSIVE)])
            - NPN: Part(tool=SKIDL, name='Q_NPN', ref_prefix='Q', pins=[Pin(num=1,name='B',func=Pin.types.INPUT), Pin(num=2,name='C',func=Pin.types.PASSIVE), Pin(num=3,name='E',func=Pin.types.PASSIVE)])
            - Op-Amp: Part(tool=SKIDL, name='OpAmp', ref_prefix='U', pins=[Pin(num=1,name='IN+',func=Pin.types.INPUT), Pin(num=2,name='IN-',func=Pin.types.INPUT), Pin(num=3,name='OUT',func=Pin.types.OUTPUT), Pin(num=4,name='V+',func=Pin.types.PWRIN), Pin(num=5,name='V-',func=Pin.types.PWRIN)])
            - Voltage Regulator (3-pin): Part(tool=SKIDL, name='REG', ref_prefix='U', pins=[Pin(num=1,name='IN',func=Pin.types.PASSIVE), Pin(num=2,name='GND',func=Pin.types.PASSIVE), Pin(num=3,name='OUT',func=Pin.types.PASSIVE)])

            Circuit completeness rules:
            - Generate COMPLETE circuits with ALL necessary components.
            - Include: bias resistors, decoupling caps, protection diodes, coupling caps.
            - Use realistic standard values (E24 resistors, standard capacitor values).
            - Minimum 8-15 components for any real circuit.

            Output format — two sections separated by exactly "---GUIDE---":

            Section 1: skidl Python code ONLY. No prose, no markdown fences.

            Section 2: Korean wiring guide:
            [부품 목록]
            - ref - 종류 값: 역할 설명

            [배선 순서]
            1. 단계별 실제 배선 방법
            """;

    private final WebClient openAiClient; // AppConfig에서 생성된 OpenAI HTTP 클라이언트
    private final ObjectMapper mapper = new ObjectMapper(); // Java 객체 ↔ JSON 변환 라이브러리
    private final Path outputsDir; // 생성된 .net 파일 저장 경로 (프로젝트 루트의 outputs/ 폴더)

    /**
     * 생성자: Spring이 AppConfig의 WebClient 빈을 자동으로 주입
     * outputs/ 디렉토리가 없으면 자동 생성 (서버 최초 실행 시 한 번만 동작)
     */
    public GenerateService(WebClient openAiClient) throws IOException {
        this.openAiClient = openAiClient;
        this.outputsDir = Path.of("outputs");
        Files.createDirectories(outputsDir);
    }

    // ════════════════════════════════════════════════════════════════
    // 메인 생성 로직 — GenerateController의 Virtual Thread에서 호출됨
    // ════════════════════════════════════════════════════════════════

    /**
     * 회로 생성 전체 흐름을 순서대로 실행하고 SseEmitter로 진행 상황을 전송
     *
     * @param description 사용자 입력 (예: "9V LED blinker 1Hz NE555")
     * @param emitter     SSE 연결 객체 — 이것을 통해 프론트엔드에 이벤트를 보냄
     */
    public void generate(String description, SseEmitter emitter) {
        try {
            // ── Step 1: 시작 알림 ────────────────────────────────────
            send(emitter, "status", "🔍 Analysing circuit requirements...");

            // GPT 대화 메시지 구성
            // messages 리스트는 GPT와의 대화 히스토리로, 역할별(system/user/assistant)로 구성됨
            // system: GPT의 역할과 출력 형식 지정
            // user: 실제 사용자 요청
            String userMsg = "Circuit request: " + description +
                    "\n\nNo reference found — use standard professional circuit design." +
                    "\n\nGenerate a COMPLETE professional-grade circuit with ALL necessary components.";

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
            messages.add(Map.of("role", "user",   "content", userMsg));

            // ── Step 2: GPT-4o 코드 생성 (최대 2회 시도) ────────────
            // GPT가 간혹 코드 앞에 설명 텍스트를 붙이는 경우가 있어서 재시도 로직 포함
            send(emitter, "status", "🤖 GPT-4o is analysing and generating the circuit...");
            String raw = callGpt(messages, "gpt-4o", 0.1, 2500);

            // 응답이 skidl 코드로 시작하지 않으면 GPT에게 다시 요청
            if (!raw.startsWith("from skidl")) {
                messages.add(Map.of("role", "assistant", "content", raw));
                messages.add(Map.of("role", "user", "content",
                        "Output only skidl Python code starting with 'from skidl import *'. Generate now."));
                raw = callGpt(messages, "gpt-4o", 0.1, 2500);
            }

            // "---GUIDE---" 기준으로 Python 코드와 한국어 배선 가이드 분리
            String[] parts   = raw.contains("---GUIDE---") ? raw.split("---GUIDE---", 2) : new String[]{raw, ""};
            String skidlCode = cleanCode(parts[0].trim()); // 마크다운 코드 블록 마커 제거
            String guide     = parts.length > 1 ? parts[1].trim() : "";

            // ── Step 3: skidl Python 실행 → .net 파일 생성 ──────────
            send(emitter, "status", "⚙️ Generating netlist...");
            String outputPath;
            try {
                outputPath = runSkidl(skidlCode);
            } catch (Exception firstErr) {
                // 첫 번째 실행 실패 시 — GPT에게 오류 메시지를 보내서 코드 수정 요청
                // 이 방식을 "셀프 힐링(self-healing)"이라고도 부름
                send(emitter, "status", "🔧 Fixing code and retrying...");

                List<Map<String, String>> fixMessages = new ArrayList<>();
                fixMessages.add(Map.of("role", "system",    "content", SYSTEM_PROMPT));
                fixMessages.add(Map.of("role", "user",      "content", userMsg));
                fixMessages.add(Map.of("role", "assistant", "content", raw));
                // GPT에게 오류 내용을 보여주면서 수정 요청
                fixMessages.add(Map.of("role", "user", "content",
                        "The code above failed with this error:\n" + firstErr.getMessage() +
                        "\n\nFix the code. Output ONLY the corrected skidl Python code followed by ---GUIDE--- and the Korean guide."));

                String fixRaw = callGpt(fixMessages, "gpt-4o", 0.05, 2500);
                String[] fp   = fixRaw.contains("---GUIDE---") ? fixRaw.split("---GUIDE---", 2) : new String[]{fixRaw, guide};
                skidlCode     = cleanCode(fp[0].trim());
                if (fp.length > 1 && !fp[1].isBlank()) guide = fp[1].trim();

                try {
                    outputPath = runSkidl(skidlCode); // 두 번째 시도
                } catch (Exception retryErr) {
                    // 두 번 모두 실패 → error 이벤트 전송 후 연결 종료
                    Map<String, String> errData = Map.of(
                            "message", "skidl execution error",
                            "detail",  retryErr.getMessage(),
                            "code",    skidlCode);
                    send(emitter, "error", mapper.writeValueAsString(errData));
                    emitter.complete();
                    return;
                }
            }

            // ── Step 4: 넷리스트 파싱 + done 이벤트 전송 ────────────
            // .net 파일을 읽어서 부품/연결 정보 추출
            NetGraph graph    = parseNetlist(Files.readString(Path.of(outputPath)));
            String   filename = Path.of(outputPath).getFileName().toString();

            // 프론트엔드 App.jsx의 evt==='done' 처리 부분에서 이 구조를 읽음
            Map<String, Object> doneData = new LinkedHashMap<>();
            doneData.put("code",     skidlCode); // ResultPanel 코드 탭에 표시
            doneData.put("guide",    guide);      // 한국어 배선 가이드 텍스트
            doneData.put("filename", filename);   // /download/{filename} 다운로드용
            doneData.put("sources",  List.of());  // 참고 URL 목록 (현재 미사용)
            doneData.put("graph",    graph);      // CircuitCanvas 렌더링 데이터

            send(emitter, "done", mapper.writeValueAsString(doneData));
            emitter.complete(); // SSE 연결 정상 종료

        } catch (Exception e) {
            // 예상치 못한 예외 발생 시 error 이벤트를 보내고 연결 종료
            try {
                Map<String, String> errData = Map.of("message", "Server error: " + e.getMessage());
                send(emitter, "error", mapper.writeValueAsString(errData));
            } catch (Exception ignored) {}
            emitter.completeWithError(e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // GPT API 호출
    // ════════════════════════════════════════════════════════════════

    /**
     * OpenAI Chat Completions API를 호출하고 GPT 응답 텍스트를 반환
     *
     * @param messages   대화 메시지 목록 (system + user, 재시도 시 assistant도 포함)
     * @param model      사용할 GPT 모델 ("gpt-4o": 고품질, "gpt-4o-mini": 빠르고 저렴)
     * @param temp       temperature 값. 0에 가까울수록 일관된 출력, 1에 가까울수록 창의적.
     *                   코드 생성이므로 0.1로 낮게 설정해서 안정적인 출력 유도.
     * @param maxTokens  GPT가 생성할 수 있는 최대 토큰(단어) 수
     *
     * WebClient 체이닝 설명:
     *   .post()           → HTTP POST 요청 준비
     *   .uri(...)         → 요청 경로 (baseUrl인 api.openai.com에 이어 붙여짐)
     *   .bodyValue(body)  → 요청 바디에 Map을 JSON으로 직렬화해서 담음
     *   .retrieve()       → 요청 전송 시작, 응답 수신 준비
     *   .bodyToMono(...)  → 응답 바디를 JsonNode 타입으로 비동기 파싱
     *   .map(...)         → JsonNode에서 실제 텍스트 내용만 추출
     *   .block()          → 비동기 작업을 동기로 기다림 (Virtual Thread에서는 안전)
     */
    private String callGpt(List<Map<String, String>> messages, String model, double temp, int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",       model);
        body.put("messages",    messages);
        body.put("temperature", temp);
        body.put("max_tokens",  maxTokens);

        return openAiClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> json
                        .path("choices").get(0) // 응답 후보 중 첫 번째 선택
                        .path("message")
                        .path("content").asText("")) // 없으면 빈 문자열 반환
                .block();
    }

    // ════════════════════════════════════════════════════════════════
    // /test_code 엔드포인트에서 재사용할 수 있도록 public 래퍼 제공
    // private 메서드를 외부에서 접근 가능하게 위임하는 패턴
    // ════════════════════════════════════════════════════════════════

    public String   runSkidlPublic(String code)  throws Exception { return runSkidl(code); }
    public NetGraph parseNetlistPublic(String t)  throws Exception { return parseNetlist(t); }

    // ════════════════════════════════════════════════════════════════
    // skidl Python 코드 실행
    // ════════════════════════════════════════════════════════════════

    /**
     * GPT가 생성한 skidl Python 코드를 실제 Python 인터프리터로 실행하여
     * KiCad 넷리스트(.net) 파일을 생성하고 outputs/ 폴더에 저장
     *
     * 처리 단계:
     *   1. 임시 폴더 생성: C:\Users\...\AppData\Local\Temp\skidl-{랜덤ID}\
     *      → 동시에 여러 요청이 들어와도 서로 파일이 겹치지 않도록 격리
     *   2. 코드를 circuit.py로 저장
     *   3. ProcessBuilder로 "python circuit.py" 실행
     *   4. 최대 90초 대기 (그 이상이면 타임아웃으로 강제 종료)
     *   5. 생성된 .net 파일을 outputs/ 폴더에 UUID 기반 고유 이름으로 복사
     *
     * ProcessBuilder:
     *   Java에서 외부 프로세스(여기서는 Python)를 실행하는 클래스.
     *   실행할 명령어, 작업 디렉토리, 환경변수 등을 설정할 수 있음.
     *
     * proc.waitFor(90, TimeUnit.SECONDS):
     *   프로세스가 끝날 때까지 최대 90초 기다림.
     *   시간 초과 시 false 반환 → destroyForcibly()로 강제 종료.
     *
     * @throws RuntimeException skidl 실행 오류, 타임아웃, .net 파일 미생성 시
     */
    private String runSkidl(String code) throws Exception {
        // 요청마다 고유한 임시 폴더 생성 (동시 요청 간 파일 충돌 방지)
        Path tmpDir   = Files.createTempDirectory("skidl-");
        Path codePath = tmpDir.resolve("circuit.py");
        Files.writeString(codePath, code);

        // Python 프로세스 설정
        ProcessBuilder pb = new ProcessBuilder("python", codePath.toString());
        pb.directory(tmpDir.toFile());  // 작업 디렉토리 = 임시 폴더
        pb.redirectErrorStream(false);  // stdout과 stderr를 분리해서 읽음
        Process proc = pb.start();

        // stderr(에러 출력) 별도 수집 — skidl 오류 메시지를 GPT에게 전달하기 위해
        StringBuilder stderr = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getErrorStream()))) {
            reader.lines().forEach(line -> stderr.append(line).append("\n"));
        }

        // 최대 90초 대기
        boolean finished = proc.waitFor(90, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new RuntimeException("skidl execution timed out");
        }

        // 프로세스 종료 코드가 0이 아니면 실행 실패 (stderr 내용을 예외로 전달)
        if (proc.exitValue() != 0) {
            throw new RuntimeException(stderr.toString());
        }

        // 임시 폴더에서 생성된 .net 파일 탐색
        Optional<Path> netFile = Files.list(tmpDir)
                .filter(p -> p.toString().endsWith(".net"))
                .findFirst();
        if (netFile.isEmpty()) {
            throw new RuntimeException("No netlist file was generated.");
        }

        // outputs/ 폴더에 UUID 기반 고유 이름으로 저장 (파일명 충돌 방지)
        String jobId      = UUID.randomUUID().toString().replace("-", "");
        Path   outputPath = outputsDir.resolve(jobId + ".net");
        Files.copy(netFile.get(), outputPath);
        return outputPath.toString();
    }

    // ════════════════════════════════════════════════════════════════
    // KiCad 넷리스트(.net) 파서
    // ════════════════════════════════════════════════════════════════

    /**
     * KiCad S-expression 형식의 .net 파일을 파싱하여 NetGraph 객체로 변환
     *
     * S-expression이란?
     *   괄호로 중첩 구조를 표현하는 형식. KiCad, Lisp 등에서 사용.
     *   예: (net (code 1) (name "VCC") (node (ref "R1") (pin "p1")))
     *
     * 파싱 전략:
     *   XML/JSON 파서 대신 정규식(Regex)으로 필요한 부분만 추출.
     *   .net 형식이 단순하고 규칙적이라 정규식으로 충분히 처리 가능.
     *
     * Pattern.compile(정규식):
     *   정규식 패턴 컴파일. 반복 사용 시 성능을 위해 미리 컴파일.
     *
     * Matcher.find():
     *   텍스트에서 패턴과 일치하는 부분을 순서대로 찾음.
     *   find()가 true를 반환할 때마다 group()으로 캡처된 값을 꺼낼 수 있음.
     *
     * @param text .net 파일 전체 내용 (문자열)
     * @return 파싱된 회로 그래프 (부품 목록 + 넷 목록)
     */
    private NetGraph parseNetlist(String text) {
        List<NetGraph.Component> components = new ArrayList<>();
        List<NetGraph.Net>       nets       = new ArrayList<>();

        // ── 부품 파싱 ────────────────────────────────────────────────
        // 찾을 패턴: (comp (ref "R1") (value "10k"))
        // group(1) = ref 값 (예: "R1"), group(2) = value 값 (예: "10k")
        Pattern compPat     = Pattern.compile(
                "\\(comp\\s*\\(ref\\s*\"([^\"]+)\"\\)\\s*\\(value\\s*\"([^\"]*)\"\\)");
        Matcher compMatcher = compPat.matcher(text);
        while (compMatcher.find()) {
            components.add(new NetGraph.Component(compMatcher.group(1), compMatcher.group(2)));
        }

        // ── 넷 파싱 ──────────────────────────────────────────────────
        // (nets ...) 섹션부터만 파싱 (부품 섹션과 섞이지 않도록)
        int netsIdx = text.indexOf("(nets");
        if (netsIdx >= 0) {
            String netSection = text.substring(netsIdx);

            // 각 넷의 시작 위치와 이름 수집
            // 패턴: (net (code 1) (name "VCC")
            Pattern  netStartPat = Pattern.compile(
                    "\\(net\\s*\\(code\\s+(\\d+)\\)\\s*\\(name\\s*\"([^\"]+)\"\\)");
            Matcher  nm          = netStartPat.matcher(netSection);
            List<int[]>  starts  = new ArrayList<>();
            List<String> names   = new ArrayList<>();
            while (nm.find()) {
                starts.add(new int[]{nm.start()});
                names.add(nm.group(2)); // 넷 이름 (예: "VCC", "GND", "OUTPUT")
            }

            // 각 넷 블록(시작~다음 넷 시작)에서 연결된 핀(node) 추출
            // 패턴: (node (ref "R1") (pin "p1"))
            Pattern nodePat = Pattern.compile(
                    "\\(node\\s*\\(ref\\s*\"([^\"]+)\"\\)\\s*\\(pin\\s*\"([^\"]+)\"\\)");

            for (int i = 0; i < starts.size(); i++) {
                int    from  = starts.get(i)[0];
                int    to    = (i + 1 < starts.size()) ? starts.get(i + 1)[0] : netSection.length();
                String block = netSection.substring(from, to); // 이 넷의 텍스트 범위만 잘라냄

                Matcher             nodeMatcher = nodePat.matcher(block);
                List<NetGraph.Node> nodes       = new ArrayList<>();
                while (nodeMatcher.find()) {
                    nodes.add(new NetGraph.Node(nodeMatcher.group(1), nodeMatcher.group(2)));
                }

                // 연결된 핀이 1개 이상인 넷만 포함 (고립된 넷 제외)
                if (!nodes.isEmpty()) {
                    nets.add(new NetGraph.Net(names.get(i), nodes));
                }
            }
        }

        return new NetGraph(components, nets);
    }

    // ════════════════════════════════════════════════════════════════
    // SSE 이벤트 전송 헬퍼
    // ════════════════════════════════════════════════════════════════

    /**
     * SseEmitter를 통해 이벤트 한 건을 클라이언트에 전송
     *
     * SSE 전송 텍스트 형식:
     *   event: status
     *   data: 🔍 Analysing...
     *   [빈 줄로 이벤트 구분]
     *
     * 브라우저의 EventSource는 이 형식을 파싱해서 onmessage/addEventListener로 전달함.
     *
     * IOException을 무시하는 이유:
     *   클라이언트가 중간에 브라우저를 닫거나 취소 버튼을 누르면 연결이 끊기면서
     *   IOException이 발생함. 이 경우는 정상적인 상황이라 에러 로그 없이 조용히 처리.
     */
    private void send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException ignored) {
            // 클라이언트 연결 끊김 — 정상 처리 (에러 아님)
        }
    }

    /**
     * GPT가 가끔 코드를 마크다운 코드 블록으로 감싸서 반환할 때 제거
     *
     * 감싸진 예:
     *   ```python
     *   from skidl import *
     *   ...
     *   ```
     *
     * 제거 후:
     *   from skidl import *
     *   ...
     */
    private String cleanCode(String code) {
        if (code.startsWith("```")) {
            String[] lines = code.split("\n");
            // 첫 줄(```python 또는 ```)과 마지막 줄(```) 제거
            return String.join("\n", Arrays.copyOfRange(lines, 1, lines.length - 1)).trim();
        }
        return code;
    }
}
