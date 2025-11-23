package com.example.mafiabot.llm;


public class LocalLLMStub implements LLMService {
    @Override
    public String generateResponse(String context) {
        // Простейшая "эмоциональная" логика — в реале отправляй context в API LLM
        if (context.contains("accuse")) {
            return "Интересно... ты уверен в своих словах? Мне кажется, это подозрительно.";
        }
        return "Я думаю, что стоит прислушаться к мнению Шерифа.";
    }
}