package com.hirain.aiagent.memory;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionChatMemoryProviderTest {

    @Test
    public void sameSessionReturnsSameChatMemoryInstance() {
        SessionChatMemoryProvider provider = new SessionChatMemoryProvider(new FakeStore(), 50);

        ChatMemory first = provider.getOrCreate("session-1");
        ChatMemory second = provider.getOrCreate("session-1");

        assertSame(first, second);
    }

    @Test
    public void differentSessionsAreIsolated() {
        SessionChatMemoryProvider provider = new SessionChatMemoryProvider(new FakeStore(), 50);

        provider.getOrCreate("session-1").add(UserMessage.from("A"));
        provider.getOrCreate("session-2").add(UserMessage.from("B"));

        assertEquals("A", ((UserMessage) provider.getOrCreate("session-1").messages().get(0)).singleText());
        assertEquals("B", ((UserMessage) provider.getOrCreate("session-2").messages().get(0)).singleText());
    }

    @Test
    public void evictsLeastRecentlyUsedSessionWhenCacheLimitExceeded() {
        SessionChatMemoryProvider provider = new SessionChatMemoryProvider(new FakeStore(), 50, 2);

        ChatMemory first = provider.getOrCreate("session-1");
        provider.getOrCreate("session-2");
        provider.getOrCreate("session-1");
        provider.getOrCreate("session-3");

        assertSame(first, provider.getOrCreate("session-1"));
    }

    @Test
    public void replaceMessagesUpdatesCachedChatMemoryImmediately() {
        FakeStore store = new FakeStore();
        SessionChatMemoryProvider provider = new SessionChatMemoryProvider(store, 50);
        ChatMemory memory = provider.getOrCreate("session-1");
        memory.add(UserMessage.from("old"));

        provider.replaceMessages("session-1", List.of(UserMessage.from("summary")));

        assertSame(memory, provider.getOrCreate("session-1"));
        assertEquals(1, provider.getOrCreate("session-1").messages().size());
        assertEquals("summary",
                ((UserMessage) provider.getOrCreate("session-1").messages().get(0)).singleText());
        assertEquals("summary",
                ((UserMessage) store.getMessages("session-1").get(0)).singleText());
    }

    @Test
    public void persistentMemoryDoesNotEvictAtLegacyWindowBoundary() {
        FakeStore store = new FakeStore();
        SessionChatMemoryProvider provider = new SessionChatMemoryProvider(store, 50);
        ChatMemory memory = provider.getOrCreate("session-long", 50);

        for (int i = 0; i < 100; i++) {
            memory.add(UserMessage.from("message-" + i));
        }

        assertEquals(100, memory.messages().size());
        assertEquals(100, store.getMessages("session-long").size());
    }

    @Test
    public void compareAndSetRejectsStaleSnapshotWithoutOverwritingNewMessage() {
        FakeStore store = new FakeStore();
        SessionChatMemoryProvider provider = new SessionChatMemoryProvider(store, 50);
        ChatMemory memory = provider.getOrCreate("session-cas");
        memory.add(UserMessage.from("original"));
        List<ChatMessage> staleSnapshot = memory.messages();
        memory.add(UserMessage.from("concurrent"));

        boolean replaced = provider.replaceMessagesIfUnchanged("session-cas",
                staleSnapshot, List.of(UserMessage.from("summary")));

        assertFalse(replaced);
        assertEquals(2, memory.messages().size());
        assertEquals("concurrent", ((UserMessage) memory.messages().get(1)).singleText());
    }

    @Test
    public void compareAndSetUpdatesLiveMemoryWhenSnapshotMatches() {
        FakeStore store = new FakeStore();
        SessionChatMemoryProvider provider = new SessionChatMemoryProvider(store, 50);
        ChatMemory memory = provider.getOrCreate("session-cas-ok");
        memory.add(UserMessage.from("original"));

        boolean replaced = provider.replaceMessagesIfUnchanged("session-cas-ok",
                memory.messages(), List.of(UserMessage.from("summary")));

        assertTrue(replaced);
        assertEquals("summary", ((UserMessage) memory.messages().get(0)).singleText());
    }

    private static final class FakeStore implements ChatMemoryStore {
        private final Map<String, String> data = new HashMap<>();

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            String json = data.get(String.valueOf(memoryId));
            return json == null ? List.of() : ChatMessageDeserializer.messagesFromJson(json);
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            data.put(String.valueOf(memoryId), ChatMessageSerializer.messagesToJson(messages));
        }

        @Override
        public void deleteMessages(Object memoryId) {
            data.remove(String.valueOf(memoryId));
        }
    }
}
