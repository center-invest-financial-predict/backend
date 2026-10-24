package com.example.demo.assistant;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads/clears the assistant conversation. The key is built exactly like for asking
 * ({@link AssistantService#conversationKey}): user + company + client chat id, so histories never mix.
 */
@Service
@RequiredArgsConstructor
public class AssistantHistoryService {

    private final ChatMemory chatMemory;
    private final AssistantService assistantService;

    public AssistantDto.HistoryResponse history(String email, String inn, String conversationId) {
        String key = assistantService.conversationKey(email, inn, conversationId);
        List<AssistantDto.HistoryMessage> messages = new ArrayList<>();
        for (Message m : chatMemory.get(key)) {
            String role = m.getMessageType() == MessageType.USER ? "user"
                    : m.getMessageType() == MessageType.ASSISTANT ? "assistant" : null;
            String text = m.getText();
            if (role == null || text == null) {
                continue;
            }
            String content = "assistant".equals(role) ? AssistantService.clean(text) : text.trim();
            if (!content.isBlank()) {
                messages.add(new AssistantDto.HistoryMessage(role, content));
            }
        }
        return new AssistantDto.HistoryResponse(inn, normalize(conversationId), messages);
    }

    public void clear(String email, String inn, String conversationId) {
        chatMemory.clear(assistantService.conversationKey(email, inn, conversationId));
    }

    private static String normalize(String conversationId) {
        return conversationId == null || conversationId.isBlank() ? "default" : conversationId.trim();
    }
}
