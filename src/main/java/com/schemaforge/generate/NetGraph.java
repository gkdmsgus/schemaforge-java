package com.schemaforge.generate;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * KiCad 넷리스트(.net) 파일을 파싱한 결과를 담는 데이터 클래스
 *
 * 생성 흐름:
 *   GPT가 skidl Python 코드 생성
 *     → Python으로 실행해서 KiCad .net 파일 생성
 *       → 이 클래스로 파싱
 *         → /generate done 이벤트의 "graph" 필드로 프론트엔드에 전달
 *           → CircuitCanvas.jsx가 받아서 화면에 회로를 그림
 *
 * KiCad .net 파일은 S-expression 형식으로 이렇게 생겼음:
 *   (components
 *     (comp (ref "R1") (value "10k") ...)
 *     (comp (ref "C1") (value "100nF") ...)
 *   )
 *   (nets
 *     (net (code 1) (name "VCC")
 *       (node (ref "R1") (pin "p1"))
 *       (node (ref "U1") (pin "V+"))
 *     )
 *   )
 *
 * 이 파일에서 추출한 데이터를 아래 세 가지 클래스로 표현함.
 */
@Getter
@AllArgsConstructor
public class NetGraph {

    private List<Component> components; // 회로에 있는 모든 부품 목록
    private List<Net> nets;             // 전기적 연결(배선) 목록

    /**
     * 부품 한 개
     *
     * KiCad .net의 <comp> 블록 하나에 대응.
     * 예: R1 - 10k 저항, C1 - 100nF 커패시터, U1 - NE555 IC
     */
    @Getter
    @AllArgsConstructor
    public static class Component {
        private String ref;   // 회로도 상 부품 식별자 (예: "R1", "C2", "U1")
        private String value; // 부품의 값 또는 모델명 (예: "10k", "100nF", "NE555")
    }

    /**
     * 넷(Net) — 전기적으로 연결된 핀들의 묶음
     *
     * 같은 넷에 속한 핀들은 전기적으로 모두 이어진 것.
     * 예: "VCC" 넷에 R1의 p1핀, U1의 V+핀이 있으면
     *     R1과 U1은 VCC 선으로 연결된 것.
     *
     * KiCad .net의 <net> 블록 하나에 대응.
     */
    @Getter
    @AllArgsConstructor
    public static class Net {
        private String name;        // 넷 이름 (예: "VCC", "GND", "OUTPUT")
        private List<Node> nodes;   // 이 넷에 연결된 핀 목록
    }

    /**
     * 넷에 연결된 핀 하나
     *
     * "어떤 부품의 어떤 핀이 이 넷에 연결되어 있는가"를 나타냄.
     * 예: ref="R1", pin="p1" → R1 저항의 p1 핀이 이 넷에 연결됨
     *
     * KiCad .net의 <node> 블록 하나에 대응.
     */
    @Getter
    @AllArgsConstructor
    public static class Node {
        private String ref; // 부품 식별자 (예: "R1", "U1")
        private String pin; // 해당 부품의 핀 이름 (예: "p1", "A", "GND", "V+")
    }
}
