package com.schemaforge.chat;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatService chatService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/chat_edit", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatEdit(@RequestBody Map<String, Object> body) {
        Object graph   = body.get("graph");
        String message = (String) body.getOrDefault("message", "");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> history =
                body.containsKey("history") ? (List<Map<String, String>>) body.get("history") : List.of();

        SseEmitter emitter = new SseEmitter(30_000L);

        if (message.isBlank() || graph == null) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("{\"message\":\"graph and message required\"}"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        executor.submit(() -> chatService.chatEdit(graph, message, history, emitter));
        return emitter;
    }
}
