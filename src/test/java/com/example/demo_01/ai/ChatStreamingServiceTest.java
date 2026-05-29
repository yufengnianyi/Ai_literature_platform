package com.example.demo_01.ai;

import com.example.demo_01.ai.memory.PersistentChatMemoryStore;
import com.example.demo_01.ai.model.DashScopeChatRequestFactory;
import com.example.demo_01.ai.model.DashScopeModelProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatStreamingServiceTest {

    @Test
    void streamShouldAnswerDirectlyWithoutRagGrounding() {
        ChatStreamingService service = new ChatStreamingService();
        StreamingChatModel streamingChatModel = mock(StreamingChatModel.class);
        PersistentChatMemoryStore memoryStore = mock(PersistentChatMemoryStore.class);
        DashScopeModelProperties modelProperties = new DashScopeModelProperties();

        when(memoryStore.getMessages("user-1::conv-1")).thenReturn(List.of());
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("direct answer");
            handler.onCompleteResponse(null);
            return null;
        }).when(streamingChatModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        ReflectionTestUtils.setField(service, "streamingChatModel", streamingChatModel);
        ReflectionTestUtils.setField(service, "persistentChatMemoryStore", memoryStore);
        ReflectionTestUtils.setField(service, "modelProperties", modelProperties);
        ReflectionTestUtils.setField(service, "chatRequestFactory", new DashScopeChatRequestFactory());

        List<ServerSentEvent<String>> events = service
                .stream("user-1::conv-1", "hello", false)
                .collectList()
                .block(Duration.ofSeconds(1));

        assertEquals("sources", events.get(0).event());
        assertEquals("[]", events.get(0).data());
        assertEquals("message", events.get(1).event());
        assertEquals("direct answer", events.get(1).data());

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(streamingChatModel).chat(requestCaptor.capture(), any(StreamingChatResponseHandler.class));
        List<ChatMessage> messages = requestCaptor.getValue().messages();
        UserMessage userMessage = (UserMessage) messages.get(messages.size() - 1);
        assertEquals("hello", userMessage.singleText());
        assertFalse(userMessage.singleText().contains("Retrieved literature snippets"));
    }
}
