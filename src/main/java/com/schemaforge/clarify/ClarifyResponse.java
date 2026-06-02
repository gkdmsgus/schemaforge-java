package com.schemaforge.clarify;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * POST /clarify 응답 JSON 구조
 *
 * GPT가 프롬프트를 분석한 뒤 두 가지 중 하나로 응답함:
 *
 * 1) 프롬프트가 충분히 명확한 경우 → 바로 회로 생성 가능
 *    { "clear": true, "questions": null }
 *
 * 2) 추가 정보가 필요한 경우 → 질문 목록 반환
 *    {
 *      "clear": false,
 *      "questions": [
 *        { "key": "power", "label": "출력 전력", "options": ["10W", "50W", "100W"] },
 *        { "key": "input_type", "label": "입력 타입", "options": ["악기", "마이크", "라인"] }
 *      ]
 *    }
 *
 * 프론트엔드(App.jsx)는 clear 값을 보고 화면 전환을 결정함:
 *   clear=true  → executeGenerate() 호출해서 회로 생성 시작
 *   clear=false → ClarifyPanel 화면 표시해서 사용자에게 질문
 *
 * @Getter          모든 필드에 getter 자동 생성 (JSON 직렬화 시 Jackson이 getter를 호출함)
 * @AllArgsConstructor  모든 필드를 받는 생성자 자동 생성
 */
@Getter
@AllArgsConstructor
public class ClarifyResponse {

    private boolean clear;            // true: 바로 생성 가능 / false: 추가 질문 필요
    private List<Question> questions; // clear=false일 때 질문 목록, clear=true면 null

    /**
     * clear=true 응답을 간편하게 만드는 정적 팩토리 메서드.
     *
     * 정적 팩토리 메서드란?
     *   new ClarifyResponse(true, null) 처럼 매번 생성자를 직접 호출하는 대신,
     *   의미 있는 이름의 메서드로 객체를 만드는 패턴.
     *   ClarifyResponse.clear() 라고 쓰면 의도가 명확하게 보임.
     *
     * 사용 예: return ClarifyResponse.clear();
     */
    public static ClarifyResponse clear() {
        return new ClarifyResponse(true, null);
    }

    /**
     * 추가 질문 한 항목을 나타내는 내부 클래스
     *
     * 내부 클래스(Inner Class)로 선언한 이유:
     *   Question은 ClarifyResponse 안에서만 쓰이는 개념이라
     *   별도 파일로 만들지 않고 여기에 묶어둠.
     *   static으로 선언해서 바깥 클래스 인스턴스 없이도 사용 가능.
     *
     * 예: { "key": "supply", "label": "공급 전압", "options": ["3.3V", "5V", "9V", "12V"] }
     */
    @Getter
    @AllArgsConstructor
    public static class Question {
        private String key;           // 영문 식별자 (프론트에서 답변 매핑에 사용, 예: "supply_voltage")
        private String label;         // 화면에 표시될 한국어 질문 텍스트 (예: "공급 전압은 몇 V인가요?")
        private List<String> options; // 사용자가 선택할 수 있는 선택지 목록
    }
}
