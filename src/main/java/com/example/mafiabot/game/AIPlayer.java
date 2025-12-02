package com.example.mafiabot.game;

import com.example.mafiabot.db.MoveDao;
import com.example.mafiabot.db.TrainingDataDao;
import com.example.mafiabot.llm.LLMService;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ИИ-игрок (мафия).
 * Делает ход, записывает его в БД и формирует обучающие данные для модели.
 */
public class AIPlayer {

    private final MoveDao moveDao;
    private final TrainingDataDao trainingDao;
    private final LLMService llmService;

    private final Random random = new Random();
    private final Gson gson = new Gson();

    public AIPlayer(MoveDao moveDao,
                    TrainingDataDao trainingDao,
                    LLMService llmService) {
        this.moveDao = moveDao;
        this.trainingDao = trainingDao;
        this.llmService = llmService;
    }

    /**
     * Простейший ИИ:
     *  - выбирает цель среди живых НЕ-мафий (в приоритете шериф);
     *  - обвиняет и "убивает" её;
     *  - пишет ход в moves и training_data;
     *  - генерирует объяснение с помощью LLM.
     */
    public String makeAccuseMove(GameSession session,
                                 long aiTelegramId,
                                 long aiDbUserId) throws Exception {

        GameManager gm = session.getManager();

        // 1. Выбор цели
        Player self = null;
        List<Player> sheriffs = new ArrayList<>();
        List<Player> towns = new ArrayList<>();

        for (Player p : gm.getPlayers()) {
            if (!p.isAlive()) continue;

            if (p.getChatId() == aiTelegramId) {
                self = p;
                continue;
            }

            // Не стреляем в других мафий
            if (p.getRole() == Role.MAFIA) {
                continue;
            }

            if (p.getRole() == Role.SHERIFF) {
                sheriffs.add(p);
            } else {
                // TOWN или null — считаем мирным
                towns.add(p);
            }
        }

        if (self == null) {
            return "ИИ ещё не добавлен в игру.";
        }
        if (sheriffs.isEmpty() && towns.isEmpty()) {
            return "ИИ пока не может сделать ход.";
        }

        Player target;
        String strategicReason;

        // Приоритет: сначала шерифы, потом обычные мирные
        if (!sheriffs.isEmpty()) {
            target = sheriffs.get(random.nextInt(sheriffs.size()));
            strategicReason =
                    "Шериф опасен для мафии, поэтому его выгодно убрать как можно раньше.";
        } else {
            target = towns.get(random.nextInt(towns.size()));
            strategicReason =
                    "Это мирный игрок, и уменьшение числа мирных повышает шансы мафии на победу.";
        }

        // 2. Логика игры: обвиняем и убиваем цель
        String resultText = gm.accuse(aiTelegramId, target.getChatId());
        gm.kill(target.getChatId());

        // 3. Записываем ход в таблицу moves
        long moveId = moveDao.insertMove(
                session.getGameId(),
                aiDbUserId,
                "ai_accuse",
                "targetTelegramId=" + target.getChatId()
        );

        // 4. Снимок состояния игры -> JSON для training_data
        GameStateSnapshot snapshot = GameStateSnapshot.fromManager(gm, "MAFIA_AI");
        String snapshotJson = gson.toJson(snapshot);

        // 5. Просим LLM сгенерировать объяснение хода
        String llmContext =
                "Ты играешь в мафию как мафия.\n" +
                        "Живые игроки: " + snapshot.aliveUsernames + "\n" +
                        "Ты выбрал цель: " + target.getUsername() + "\n" +
                        "Опиши коротко, почему мафия могла выбрать именно его.";
        String explanation = llmService.generateResponse(llmContext);

        // 6. Сохраняем обучающий пример
        trainingDao.insertTrainingRow(
                session.getGameId(),
                moveId,
                "mafia_ai",
                snapshotJson,
                "accuse:" + target.getUsername(),
                "unknown"   // итог (win/lose) проставляется при завершении игры
        );

        // 7. Формируем текст для чата
        return "ИИ (мафия) делает ход: " + resultText +
                ". Игрок " + target.getUsername() + " выбывает из игры.\n" +
                "Стратегия: " + strategicReason + "\n" +
                "Объяснение ИИ: " + explanation;
    }

    /**
     * Упрощённый снимок состояния игры для записи в training_data.
     */
    public static class GameStateSnapshot {
        public int aliveCount;
        public int totalPlayers;
        public String aiRole;
        public List<String> aliveUsernames;

        public static GameStateSnapshot fromManager(GameManager gm, String aiRole) {
            GameStateSnapshot s = new GameStateSnapshot();
            s.totalPlayers = gm.getPlayers().size();
            s.aliveUsernames = new ArrayList<>();
            int alive = 0;
            for (Player p : gm.getPlayers()) {
                if (p.isAlive()) {
                    alive++;
                    s.aliveUsernames.add(p.getUsername());
                }
            }
            s.aliveCount = alive;
            s.aiRole = aiRole;
            return s;
        }
    }
}
