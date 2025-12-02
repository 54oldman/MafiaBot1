package com.example.mafiabot.llm;

public class LocalLLMStub implements LLMService {

    @Override
    public String generateResponse(String context) {
        if (context == null || context.isBlank()) {
            return "Сложно сказать, пока нет информации.";
        }
        if (context.toLowerCase().contains("выбрал цель")) {
            return "Он казался наиболее подозрительным по прошлым действиям.";
        }
        return "Я считаю, что этот игрок ведёт себя подозрительно и может быть мафией.";
    }
}
