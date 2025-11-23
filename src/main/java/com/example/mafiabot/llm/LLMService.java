package com.example.mafiabot.llm;
public interface LLMService {
    /**
     * Сформировать реплику от имени виртуального игрока на основе контекста.
     * @param context - сериализованный контекст игры (роли, ходы, раунды)
     * @return текст реплики
     */
    String generateResponse(String context);
}