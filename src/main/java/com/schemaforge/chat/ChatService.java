package com.schemaforge.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

@Service
public class ChatService {

    private final WebClient openAiClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatService(WebClient openAiClient) {
        this.openAiClient = openAiClient;
    }

    @SuppressWarnings("unchecked")
    public void chatEdit(Object graph, String message,
                         List<Map<String, String>> history,
                         SseEmitter emitter) {
        try {
            // Build component/net summary from graph
            Map<String, Object> g = (Map<String, Object>) graph;
            List<Map<String, Object>> comps = (List<Map<String, Object>>) g.getOrDefault("components", List.of());
            List<Map<String, Object>> nets  = (List<Map<String, Object>>) g.getOrDefault("nets", List.of());

            String compSummary = comps.stream()
                    .map(c -> c.get("ref") + "(" + c.getOrDefault("value", "?") + ")")
                    .reduce((a, b) -> a + ", " + b).orElse("none");
            String netSummary = nets.stream()
                    .map(n -> {
                        List<Map<String, String>> nodes = (List<Map<String, String>>) n.getOrDefault("nodes", List.of());
                        String refs = nodes.stream().map(nd -> (String) nd.get("ref"))
                                .reduce((a, b) -> a + "," + b).orElse("");
                        return n.get("name") + ":[" + refs + "]";
                    }).reduce((a, b) -> a + ", " + b).orElse("none");

            String systemPrompt = """
                    You are a circuit editor assistant. The user has a schematic and wants to modify it.

                    Current circuit:
                    - Components: %s
                    - Nets: %s

                    Always call the edit_circuit function. Reply in Korean (1-2 sentences).
                    """.formatted(compSummary, netSummary);

            // Build messages
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            for (Map<String, String> h : history) {
                messages.add(Map.of("role", h.get("role"), "content", h.get("content")));
            }
            messages.add(Map.of("role", "user", "content", message));

            // Tool definition
            Map<String, Object> tool = Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", "edit_circuit",
                            "description", "Apply circuit edits and reply to the user",
                            "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "reply", Map.of("type", "string"),
                                            "actions", Map.of(
                                                    "type", "array",
                                                    "items", Map.of(
                                                            "type", "object",
                                                            "properties", Map.of(
                                                                    "type",  Map.of("type", "string"),
                                                                    "ref",   Map.of("type", "string"),
                                                                    "name",  Map.of("type", "string"),
                                                                    "value", Map.of("type", "string")
                                                            ),
                                                            "required", List.of("type")
                                                    )
                                            )
                                    ),
                                    "required", List.of("reply", "actions")
                            )
                    )
            );

            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("model",        "gpt-4o-mini");
            reqBody.put("messages",     messages);
            reqBody.put("tools",        List.of(tool));
            reqBody.put("tool_choice",  Map.of("type", "function", "function", Map.of("name", "edit_circuit")));
            reqBody.put("temperature",  0);
            reqBody.put("max_tokens",   700);

            JsonNode response = openAiClient.post()
                    .uri("/v1/chat/completions")
                    .bodyValue(reqBody)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            String args = Optional.ofNullable(response)
                    .map(r -> r.path("choices").get(0))
                    .map(c -> c.path("message").path("tool_calls").get(0))
                    .map(tc -> tc.path("function").path("arguments").asText("{}"))
                    .orElse("{}");

            JsonNode parsed     = mapper.readTree(args);
            String   reply      = parsed.path("reply").asText("완료했습니다.");
            JsonNode actionsNode = parsed.path("actions");
            Object   actions    = mapper.treeToValue(actionsNode, Object.class);

            // Stream reply text char by char (simple simulation)
            send(emitter, "text", mapper.writeValueAsString(reply));

            Map<String, Object> done = Map.of("reply", reply, "actions", actions);
            send(emitter, "done", mapper.writeValueAsString(done));
            emitter.complete();

        } catch (Exception e) {
            try {
                send(emitter, "error", mapper.writeValueAsString(
                        Map.of("message", e.getMessage() != null ? e.getMessage() : "서버 오류")));
            } catch (Exception ignored) {}
            emitter.completeWithError(e);
        }
    }

    private void send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException ignored) {}
    }
}
