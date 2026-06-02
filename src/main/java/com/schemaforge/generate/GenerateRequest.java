package com.schemaforge.generate;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * POST /generate 요청 바디를 담는 DTO 클래스
 *
 * 프론트엔드에서 이런 JSON을 보내면:
 *   { "description": "9V battery LED blinker 1Hz NE555" }
 * Spring이 자동으로 이 객체로 변환해서 컨트롤러에 전달함.
 *
 * @Getter          → getDescription() 메서드 자동 생성
 * @NoArgsConstructor → Jackson이 JSON 변환 시 사용하는 기본 생성자 자동 생성
 */
@Getter
@NoArgsConstructor
public class GenerateRequest {
    private String description; // 사용자가 입력한 회로 설명 텍스트
}
