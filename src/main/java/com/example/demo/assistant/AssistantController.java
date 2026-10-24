package com.example.demo.assistant;

import com.example.demo.assistant.AssistantDto.AskRequest;
import com.example.demo.assistant.AssistantDto.AskResponse;
import com.example.demo.assistant.AssistantDto.ContextResponse;
import com.example.demo.service.CompanyAccessService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/companies/{inn}/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final CompanyAccessService companyAccessService;
    private final AssistantService assistantService;
    private final AssistantHistoryService historyService;

    @PostMapping("/ask")
    @Operation(summary = "Explain the current forecast (or a scenario / a day) with the LLM; numbers come from the calculation")
    public ResponseEntity<AskResponse> ask(
            @PathVariable String inn,
            @Valid @RequestBody AskRequest request,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(assistantService.ask(authentication.getName(), inn, request));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Same as /ask, streamed as server-sent events")
    public Flux<String> stream(
            @PathVariable String inn,
            @Valid @RequestBody AskRequest request,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return assistantService.stream(authentication.getName(), inn, request);
    }

    @GetMapping("/history")
    @Operation(summary = "Messages of the current user's conversation about this company (conversationId optional)")
    public ResponseEntity<AssistantDto.HistoryResponse> history(
            @PathVariable String inn,
            @RequestParam(required = false) String conversationId,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(historyService.history(authentication.getName(), inn, conversationId));
    }

    @DeleteMapping("/history")
    @Operation(summary = "Clear the current user's conversation about this company")
    public ResponseEntity<Void> clearHistory(
            @PathVariable String inn,
            @RequestParam(required = false) String conversationId,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        historyService.clear(authentication.getName(), inn, conversationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/context")
    @Operation(summary = "Facts block the assistant receives for this company (and scenario/day)")
    public ResponseEntity<ContextResponse> context(
            @PathVariable String inn,
            @RequestParam(required = false) Long scenarioId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(assistantService.context(authentication.getName(), inn, scenarioId, null, date));
    }
}
