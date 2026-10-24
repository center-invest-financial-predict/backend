package com.example.demo.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class AssistantDto {

    private AssistantDto() {
    }

    /**
     * @param scenarioId     saved scenario of the current user to explain (optional)
     * @param conditions     typed scenario conditions to explain without saving (optional, used if no scenarioId)
     * @param date           forecast day to focus on (optional), e.g. when explaining a point of the chart
     * @param conversationId client-side chat id; it is always namespaced by user and company on the server
     */
    public record AskRequest(
            @NotBlank @Size(max = 2000) String question,
            Long scenarioId,
            Object conditions,
            LocalDate date,
            String conversationId
    ) {
    }

    /** Key numbers of the calculation the answer is based on (computed by the engine, never by the LLM). */
    public record Figures(
            LocalDate dataAsOf,
            BigDecimal currentBalance,
            BigDecimal endBalance,
            BigDecimal minBalance,
            LocalDate minBalanceDate,
            LocalDate gapDate,
            BigDecimal gapAmount,
            BigDecimal gapProbability,
            LocalDate riskGapDate,
            BigDecimal riskGapAmount
    ) {
    }

    public record AskResponse(
            String answer,
            /** llm | fallback (LLM unavailable: deterministic summary of the same calculation) */
            String source,
            String runId,
            LocalDateTime calculatedAt,
            List<String> scenario,
            Figures figures,
            /** Short reason when source = fallback (or when the primary provider failed and another one answered). */
            String note,
            /** Who produced the text: ml-core (server /v1/chat) | local (own LLM) | none (deterministic summary). */
            String provider,
            /** The model asks the user to specify something instead of applying a scenario. */
            boolean clarificationNeeded,
            /** Scenarios the model understood from the question; the numbers in this response already include them. */
            List<AppliedScenario> appliedScenarios,
            /** Scenario conditions the numbers are calculated with (machine form, can be saved as a scenario). */
            List<Map<String, Object>> conditions
    ) {
    }

    /**
     * @param supported false when our engine cannot calculate this kind of scenario; it is then NOT in the numbers
     * @param condition machine form of the condition our engine applied (null if not supported)
     */
    public record AppliedScenario(String id, String kind, String description, Map<String, Object> condition,
                                  boolean supported, String reason) {
    }

    public record ContextResponse(String facts, Figures figures, String runId, LocalDateTime calculatedAt) {
    }
}
