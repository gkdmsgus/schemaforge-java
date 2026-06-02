package com.schemaforge.clarify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ClarifyService {

    private static final String SYSTEM_PROMPT = """
            You are a circuit design assistant. Given a user's natural-language circuit request (in Korean or English), decide if it is specific enough to generate a complete schematic immediately, or if 2–4 clarifying questions would significantly improve the result.

            Output JSON ONLY in this exact shape:
            { "clear": true }
            or
            { "clear": false, "questions": [ { "key": "string", "label": "string (Korean)", "options": ["string", ...] }, ... ] }

            Rules:
            - STRONGLY default to "clear": true. Only return false when the prompt is genuinely under-specified.
            - A prompt is CLEAR if it names: the circuit purpose AND at least one critical spec (voltage, output power, IC, frequency, sensor type, count, etc).
            - A prompt is NOT CLEAR if it is just a category with no specs.
            - Ask AT MOST 4 questions, AT LEAST 2.
            - Each question: short Korean label + 3–5 short option strings (also Korean).
            - Pick HIGH-IMPACT specs only.
            - Use stable English snake_case keys.
            - If the user explicitly asks for something specific in their prompt, do NOT re-ask that.
            - Do not output anything except the JSON.
            """;

    private final WebClient openAiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // AppConfig에서 만든 WebClient 빈이 자동으로 주입됨
    public ClarifyService(WebClient openAiClient) {
        this.openAiClient = openAiClient;
    }

    public ClarifyResponse clarify(String description) {
        try {
            // Node.js: openai.chat.completions.create({ model, messages, ... })
            Map<String, Object> body = Map.of(
                "model", "gpt-4o-mini",
                "temperature", 0.2,
                "max_tokens", 500,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user",   "content", description)
                )
            );

            String raw = openAiClient.post()
                    .uri("/v1/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .map(json -> json
                            .path("choices").get(0)
                            .path("message")
                            .path("content").asText("{\"clear\":true}"))
                    .block(); // 동기 대기 (추후 /generate에서는 스트리밍으로 변경)

            return parse(raw);
        } catch (Exception e) {
            // Node.js의 catch (e) { res.json({ clear: true }) } 와 동일
            return ClarifyResponse.clear();
        }
    }

    private ClarifyResponse parse(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            boolean clear = node.path("clear").asBoolean(true);
            if (clear || !node.has("questions")) return ClarifyResponse.clear();

            List<ClarifyResponse.Question> questions = new ArrayList<>();
            for (JsonNode q : node.path("questions")) {
                String key   = q.path("key").asText();
                String label = q.path("label").asText();
                List<String> options = new ArrayList<>();
                q.path("options").forEach(o -> options.add(o.asText()));
                questions.add(new ClarifyResponse.Question(key, label, options));
            }
            if (questions.isEmpty()) return ClarifyResponse.clear();
            return new ClarifyResponse(false, questions.subList(0, Math.min(4, questions.size())));
        } catch (Exception e) {
            return ClarifyResponse.clear();
        }
    }
}
