package com.example.demo.controller;

import com.example.demo.service.AiService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    // Обычный запрос
    @GetMapping("/ask")
    public String ask(
            @RequestParam String question,
            @RequestParam(defaultValue = "default") String conversationId,
            Authentication authentication) {
        return aiService.ask(scoped(authentication, conversationId), question);
    }

    // Стриминг (curl -N, или EventSource на фронте)
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(
            @RequestParam String question,
            @RequestParam(defaultValue = "default") String conversationId,
            Authentication authentication) {
        return aiService.askStreaming(scoped(authentication, conversationId), question);
    }

    /** Chat memory is private to the authenticated user: the client id is only a suffix inside their namespace. */
    private static String scoped(Authentication authentication, String conversationId) {
        String chat = conversationId == null ? "default" : conversationId.replaceAll("[^A-Za-z0-9_.-]", "_");
        return "user:" + authentication.getName() + ":" + chat;
    }
}