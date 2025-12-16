package com.example.mafiabot.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * LLM-сервис, который сначала пробует сходить в OpenAI,
 * а при ошибках (особенно 429) падает обратно на LocalLLMStub.
 */
public class OpenAiLLMService implements LLMService {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient();
    private final String apiKey;
    private final String model;

    // Локальная заглушка – сюда откатываемся при 429 и прочих проблемах
    private final LLMService fallbackStub = new LocalLLMStub();

    public OpenAiLLMService(String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "OpenAI API key is null/blank. " +
                            "Убедись, что переменная окружения OPENAI_API_KEY задана в конфигурации запуска."
            );
        }
        this.apiKey = apiKey;
        this.model = (model == null || model.isBlank())
                ? "gpt-4o-mini"
                : model;
    }

    @Override
    public String generateResponse(String context) {
        // если совсем нет контекста – подставим дефолтный
        if (context == null || context.isBlank()) {
            context = "Кратко объясни свой ход в мафии.";
        }

        try {
            // --- формируем JSON-запрос к OpenAI ---
            JsonObject root = new JsonObject();
            root.addProperty("model", model);

            JsonArray messages = new JsonArray();

            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty(
                    "content",
                    "Ты — объяснимый ИИ, который кратко и понятно объясняет свои " +
                            "ходы в настольной игре 'Мафия'. Отвечай по-русски, 1–3 предложения."
            );
            messages.add(systemMsg);

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", context);
            messages.add(userMsg);

            root.add("messages", messages);
            root.addProperty("temperature", 0.7);
            root.addProperty("max_tokens", 128);

            RequestBody body = RequestBody.create(root.toString(), JSON);

            Request request = new Request.Builder()
                    .url(API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody =
                            response.body() != null ? response.body().string() : "";
                    System.err.println(
                            "OpenAI API error: HTTP " + response.code() + " " + errorBody
                    );

                    // === КЛЮЧЕВОЕ МЕСТО: обработка 429 ===
                    if (response.code() == 429) {
                        // Превышен лимит / слишком часто вызываем API – откатываемся на заглушку
                        String fallback = fallbackStub.generateResponse(context);
                        return fallback + " (ограничение OpenAI, использую локальное объяснение)";
                    }

                    // Для других кодов тоже можно откатываться на заглушку
                    String fallback = fallbackStub.generateResponse(context);
                    return fallback + " (ошибка связи с OpenAI: " + response.code() + ")";
                }

                String responseBody =
                        response.body() != null ? response.body().string() : "";
                JsonObject json =
                        JsonParser.parseString(responseBody).getAsJsonObject();

                JsonArray choices = json.getAsJsonArray("choices");
                if (choices == null || choices.size() == 0) {
                    return fallbackStub.generateResponse(context) +
                            " (LLM вернула пустой ответ)";
                }

                JsonObject firstChoice = choices.get(0).getAsJsonObject();
                JsonObject message = firstChoice.getAsJsonObject("message");
                if (message == null || !message.has("content")) {
                    return fallbackStub.generateResponse(context) +
                            " (LLM ответила в неожиданном формате)";
                }

                String content = message.get("content").getAsString();
                if (content == null || content.isBlank()) {
                    return fallbackStub.generateResponse(context) +
                            " (LLM вернула пустой ответ)";
                }

                return content.trim();
            }
        } catch (Exception e) {
            e.printStackTrace();
            // На любые исключения тоже откатываемся на локальное объяснение
            String fallback = fallbackStub.generateResponse(context);
            return fallback + " (исключение при обращении к OpenAI: " + e.getMessage() + ")";
        }
    }
}
