package com.schemaforge.clarify;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * POST /clarify HTTP 엔드포인트 컨트롤러
 *
 * 컨트롤러(Controller)의 역할:
 *   HTTP 요청을 받아서 Service에 처리를 위임하고, 결과를 HTTP 응답으로 돌려줌.
 *   비즈니스 로직(GPT 호출 등)은 여기에 작성하지 않음 — 그것은 Service의 몫.
 *   "요청을 받고 결과를 내보내는 창구" 역할만 담당.
 *
 * @RestController
 *   @Controller + @ResponseBody 를 합친 어노테이션.
 *   @Controller만 쓰면 반환값을 뷰(HTML)로 처리하려 하는데,
 *   @ResponseBody가 추가되면 반환값을 JSON으로 직렬화해서 응답 바디에 담음.
 *   REST API를 만들 때는 항상 @RestController 사용.
 *
 * @CrossOrigin(origins = "*")
 *   CORS(Cross-Origin Resource Sharing) 허용 설정.
 *   브라우저는 보안상 다른 출처(포트, 도메인)의 서버에 요청을 막는데,
 *   이 어노테이션으로 모든 출처에서의 요청을 허용함.
 *   (프론트: localhost:3000 → 백엔드: localhost:8080 — 포트가 달라 CORS 필요)
 */
@RestController
@CrossOrigin(origins = "*")
public class ClarifyController {

    private final ClarifyService clarifyService;

    /**
     * 생성자 주입 (Constructor Injection)
     *
     * Spring의 의존성 주입 방식 중 하나.
     * 생성자 파라미터로 ClarifyService를 선언하면,
     * Spring이 앱 시작 시 ClarifyService 빈을 찾아서 자동으로 여기에 넣어줌.
     * 개발자가 직접 new ClarifyService()를 호출할 필요가 없음.
     *
     * 생성자 주입을 권장하는 이유:
     *   - 객체 생성 시점에 의존성이 확정되어 불변성 보장
     *   - 테스트 시 Mock 객체를 쉽게 주입 가능
     *   - 순환 의존성 문제를 컴파일 시점에 감지
     */
    public ClarifyController(ClarifyService clarifyService) {
        this.clarifyService = clarifyService;
    }

    /**
     * POST /clarify 요청 처리
     *
     * @PostMapping("/clarify")
     *   HTTP POST 메서드로 /clarify 경로에 들어오는 요청을 이 메서드가 처리함.
     *
     * @RequestBody ClarifyRequest request
     *   HTTP 요청 바디의 JSON을 ClarifyRequest 객체로 자동 변환(역직렬화).
     *   Jackson 라이브러리가 이 작업을 수행함.
     *   예: {"description":"베이스 앰프"} → request.getDescription() = "베이스 앰프"
     *
     * ResponseEntity<?>
     *   HTTP 응답 상태코드 + 바디를 함께 제어할 수 있는 래퍼 클래스.
     *   <?> 제네릭 와일드카드: 정상 응답(ClarifyResponse)과 에러 응답(Map) 두 가지
     *   타입을 모두 반환할 수 있어서 사용.
     *
     * ResponseEntity.badRequest()  → HTTP 400 Bad Request
     * ResponseEntity.ok()          → HTTP 200 OK
     */
    @PostMapping("/clarify")
    public ResponseEntity<?> clarify(@RequestBody ClarifyRequest request) {

        String description = request.getDescription();

        // 입력값 유효성 검사: description이 없거나 공백만 있으면 400 에러 반환
        if (description == null || description.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "description required"));
        }

        // 실제 GPT 호출은 Service에 위임
        ClarifyResponse response = clarifyService.clarify(description.trim());

        // 200 OK + ClarifyResponse 객체를 JSON으로 직렬화해서 반환
        return ResponseEntity.ok(response);
    }
}
