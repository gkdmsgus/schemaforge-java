package com.schemaforge.clarify;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * POST /clarify 요청 바디를 담는 클래스 (DTO - Data Transfer Object)
 *
 * DTO란?
 *   계층 간 데이터 전달만을 목적으로 하는 단순한 클래스.
 *   비즈니스 로직 없이 데이터를 담고 꺼내는 역할만 함.
 *
 * 프론트엔드에서 이런 형식으로 JSON을 보내면:
 *   { "description": "베이스 앰프" }
 * Spring이 자동으로 이 클래스의 객체로 변환해줌.
 *
 * @Getter
 *   Lombok 라이브러리 어노테이션. getDescription() 같은 getter 메서드를
 *   컴파일 시점에 자동으로 생성해줌. 직접 작성하면 코드가 길어지므로 생략.
 *
 * @NoArgsConstructor
 *   파라미터 없는 기본 생성자를 자동 생성.
 *   Jackson(JSON 변환 라이브러리)이 JSON → 객체 변환 시 기본 생성자를 먼저 호출하기 때문에 필요.
 */
@Getter
@NoArgsConstructor
public class ClarifyRequest {
    private String description; // 사용자가 입력한 회로 설명 (예: "베이스 앰프", "9V LED blinker")
}
