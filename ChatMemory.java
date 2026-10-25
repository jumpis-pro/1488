package com.example.aiplayers.ai;

import net.neoforged.neoforge.event.ServerChatEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Последние сообщения чата - боты "слышат" игроков и могут ответить. */
public final class ChatMemory {

    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final AtomicInteger PLAYER_CHAT_VERSION = new AtomicInteger();

    private ChatMemory() {}

    public static synchronized void add(String line) {
        LINES.addLast(line);
        while (LINES.size() > 12) LINES.removeFirst();
    }

    public static synchronized List<String> recent(int n) {
        List<String> all = new ArrayList<>(LINES);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    /** Растёт только от сообщений живых игроков, чтобы боты не заводили друг друга в бесконечный диалог. */
    public static int playerChatVersion() {
        return PLAYER_CHAT_VERSION.get();
    }

    public static void onChat(ServerChatEvent event) {
        add(event.getUsername() + ": " + event.getRawText());
        PLAYER_CHAT_VERSION.incrementAndGet();
    }
}
