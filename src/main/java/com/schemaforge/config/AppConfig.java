package com.schemaforge.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 애플리케이션 전체 설정 클래스
 *
 * @Configuration
 *   이 클래스가 "설정 파일"임을 Spring에 알려주는 어노테이션.
 *   Spring은 앱 시작 시 이 클래스를 스캔하여 내부의 @Bean 메서드를 모두 실행하고,
 *   반환된 객체들을 "빈(Bean)"으로 등록해 전체 앱에서 재사용할 수 있게 관리함.
 *
 * 빈(Bean)이란?
 *   Spring이 생성하고 관리하는 객체. 개발자가 new로 직접 만들지 않아도
 *   Spring이 필요한 곳에 자동으로 넣어줌. 이것을 "의존성 주입(DI)"이라고 함.
 */
@Configuration
public class AppConfig {

    /**
     * .env 파일을 읽어서 환경변수를 제공하는 Dotenv 빈
     *
     * .env 파일에는 외부에 노출되면 안 되는 API 키, 비밀번호 등을 저장함.
     * 코드에 직접 API 키를 쓰면 GitHub에 올릴 때 노출될 위험이 있어서
     * 이 방식으로 분리해서 관리함.
     *
     * directory("./")
     *   프로젝트 루트 폴더(C:\schemaforge-java\)에서 .env 파일을 찾음.
     *
     * ignoreIfMissing()
     *   .env 파일이 없어도 에러 없이 실행됨.
     *   실제 서버(AWS, GCP 등) 배포 시에는 .env 대신 서버 환경변수를 사용하는데,
     *   그 경우에도 코드 변경 없이 동작하도록 하기 위한 설정.
     */
    @Bean
    public Dotenv dotenv() {
        return Dotenv.configure()
                .directory("./")
                .ignoreIfMissing()
                .load();
    }

    /**
     * OpenAI API 호출에 사용할 HTTP 클라이언트 빈
     *
     * WebClient는 Spring WebFlux가 제공하는 HTTP 클라이언트.
     * 여기서 baseUrl과 공통 헤더(인증 정보 등)를 미리 설정해두면,
     * 실제 호출 코드에서는 경로("/v1/chat/completions")만 지정하면 됨.
     *
     * 파라미터 Dotenv dotenv
     *   Spring이 위에서 만든 Dotenv 빈을 이 파라미터에 자동으로 주입함.
     *   이것이 의존성 주입의 핵심: 개발자가 객체를 직접 전달하지 않아도
     *   Spring이 알아서 연결해줌.
     *
     * Authorization 헤더
     *   OpenAI API는 모든 요청에 "Bearer {API_KEY}" 형식의 인증 헤더를 요구함.
     *   defaultHeader로 설정하면 이 클라이언트로 보내는 모든 요청에 자동으로 포함됨.
     *
     * maxInMemorySize(4MB)
     *   WebClient는 응답 데이터를 메모리에 버퍼링하는데, 기본값이 256KB임.
     *   GPT-4o의 회로 생성 응답은 길어서 기본값으로는 잘릴 수 있어 4MB로 늘림.
     */
    @Bean
    public WebClient openAiClient(Dotenv dotenv) {
        String apiKey = resolve(dotenv, "OPENAI_API_KEY");
        return WebClient.builder()
                .baseUrl("https://api.openai.com")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    // .env 우선, 없으면 OS 환경변수에서 읽음 (Railway/Docker 배포 지원)
    private String resolve(Dotenv dotenv, String key) {
        String val = dotenv.get(key, null);
        if (val != null && !val.isBlank()) return val;
        val = System.getenv(key);
        return val != null ? val : "";
    }
}
