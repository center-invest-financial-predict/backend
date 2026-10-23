package com.example.demo.controller;

import com.example.demo.service.AiService;
import org.springframework.http.MediaType;
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
            @RequestParam(defaultValue = "default") String conversationId) {
        return aiService.ask(conversationId, question);
    }

    // Стриминг (curl -N, или EventSource на фронте)
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(
            @RequestParam String question,
            @RequestParam(defaultValue = "default") String conversationId) {
        return aiService.askStreaming(conversationId, question);
    }
}