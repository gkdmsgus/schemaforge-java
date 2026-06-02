package com.schemaforge.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class PlanService {

    private static final String SYSTEM_PROMPT = """
            You are a senior circuit designer. Given a user's request (Korean or English), produce a concise design plan BEFORE the schematic is built.

            Output JSON ONLY in this exact shape:
            {
              "title": "string (Korean, ≤ 30 chars)",
              "summary": "string (Korean, 1–2 sentences)",
              "topology": "string (Korean, ≤ 80 chars)",
              "specs": [ { "label": "string", "value": "string" } ],
              "parts": [ { "ref": "string", "type": "string", "value": "string", "role": "string" } ],
              "risks": [ "string" ]
            }
            Output ONLY the JSON object. No markdown, no prose.
            """;

    private final WebClient openAiClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlanService(WebClient openAiClient) {
        this.openAiClient = openAiClient;
    }

    public Object plan(String description) throws Exception {
        Map<String, Object> body = Map.of(
                "model", "gpt-4o-mini",
                "temperature", 0.3,
                "max_tokens", 900,
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
                .map(json -> json.path("choices").get(0).path("message").path("content").asText("{}"))
                .block();

        return mapper.readValue(raw, Object.class);
    }
}
