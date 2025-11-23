package com.example.mafiabot.game;
import java.util.HashMap;
import java.util.Map;

public class GameSession {
    private final long chatId;
    private final long gameId;
    private final GameManager manager;
    private final Map<Long, Long> telegramToUserId = new HashMap<>();

    public GameSession(long chatId, long gameId, GameManager manager) {
        this.chatId = chatId;
        this.gameId = gameId;
        this.manager = manager;
    }

    public long getChatId() { return chatId; }
    public long getGameId() { return gameId; }
    public GameManager getManager() { return manager; }

    public void putUserMapping(long telegramId, long dbUserId) {
        telegramToUserId.put(telegramId, dbUserId);
    }

    public Long getDbUserId(long telegramId) {
        return telegramToUserId.get(telegramId);
    }
}
