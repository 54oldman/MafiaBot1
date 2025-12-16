package com.example.mafiabot.game;

import com.example.mafiabot.db.MoveDao;
import com.example.mafiabot.db.TrainingDataDao;
import com.example.mafiabot.llm.LLMService;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ИИ-логика:
 *  - ночной выбор цели для мафии;
 *  - объяснение дневного голоса бота через LLM.
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
     * Результат решения мафии на ночь.
     */
    public static class MafiaDecision {
        public final long targetTelegramId;
        public final String targetUsername;
        public final String strategicReason;
        public final String explanation;
        public final long moveId;

        public MafiaDecision(long targetTelegramId,
                             String targetUsername,
                             String strategicReason,
                             String explanation,
                             long moveId) {
            this.targetTelegramId = targetTelegramId;
            this.targetUsername = targetUsername;
            this.strategicReason = strategicReason;
            this.explanation = explanation;
            this.moveId = moveId;
        }
    }

    /**
     * ИИ-мафия выбирает цель убийства:
     *  - не стреляет в себя и других мафий;
     *  - приоритет: SHERIFF, затем остальные мирные/доктор;
     *  - логирует ход и возвращает решение.
     *
     * Игрок фактически НЕ убивается здесь, только выбирается.
     */
    public MafiaDecision decideMafiaTarget(GameSession session,
                                           long aiTelegramId,
                                           long aiDbUserId) throws Exception {

        GameManager gm = session.getManager();

        Player self = null;
        List<Player> sheriffs = new ArrayList<>();
        List<Player> towns = new ArrayList<>();

        for (Player p : gm.getPlayers()) {
            if (!p.isAlive()) continue;

            if (p.getChatId() == aiTelegramId) {
                self = p;
                continue;
            }

            if (p.getRole() == Role.MAFIA) {
                continue; // своих не трогаем
            }

            if (p.getRole() == Role.SHERIFF) {
                sheriffs.add(p);
            } else {
                towns.add(p); // TOWN/DOCTOR/неизвестно
            }
        }

        if (self == null) {
            return null;
        }
        if (sheriffs.isEmpty() && towns.isEmpty()) {
            return null;
        }

        Player target;
        String strategicReason;

        if (!sheriffs.isEmpty()) {
            target = sheriffs.get(random.nextInt(sheriffs.size()));
            strategicReason =
                    "Шериф опасен для мафии, поэтому его выгодно убрать как можно раньше.";
        } else {
            target = towns.get(random.nextInt(towns.size()));
            strategicReason =
                    "Уменьшение числа мирных повышает шансы мафии на победу.";
        }

        // просто добавим обвинение в историю
        gm.accuse(aiTelegramId, target.getChatId());

        // Запись хода в moves
        long moveId = moveDao.insertMove(
                session.getGameId(),
                aiDbUserId,
                "ai_night_choice",
                "targetTelegramId=" + target.getChatId()
        );

        // Снимок состояния для training_data
        GameStateSnapshot snapshot = GameStateSnapshot.fromManager(gm, "MAFIA_AI");
        String snapshotJson = gson.toJson(snapshot);

        // Объяснение от LLM
        String llmContext =
                "Ты играешь в мафию как мафия.\n" +
                        "Живые игроки: " + snapshot.aliveUsernames + "\n" +
                        "Ты выбрал цель: " + target.getUsername() + "\n" +
                        "Кратко объясни, почему мафия могла выбрать именно этого игрока.";
        String explanation = llmService.generateResponse(llmContext);

        // Обучающий пример
        trainingDao.insertTrainingRow(
                session.getGameId(),
                moveId,
                "mafia_ai",
                snapshotJson,
                "chooseKill:" + target.getUsername(),
                "unknown"
        );

        return new MafiaDecision(
                target.getChatId(),
                target.getUsername(),
                strategicReason,
                explanation,
                moveId
        );
    }

    /**
     * Объяснение дневного голоса бота.
     * Используется в GameController.autoBotVotes().
     */
    public String explainDayVote(GameManager gm, Player bot, Player target) {
        try {
            GameStateSnapshot snapshot = GameStateSnapshot.fromManager(
                    gm,
                    bot.getRole() != null ? bot.getRole().name() : "UNKNOWN"
            );

            String roleText = (bot.getRole() != null) ? bot.getRole().name() : "UNKNOWN";

            String context =
                    "Сейчас идёт дневное голосование в настольной игре \"Мафия\".\n" +
                            "Ты играешь за роль: " + roleText + ".\n" +
                            "Живые игроки: " + snapshot.aliveUsernames + ".\n" +
                            "Ты решил проголосовать против игрока: " + target.getUsername() + ".\n" +
                            "Кратко и по-русски объясни, почему такой выбор кажется логичным " +
                            "с точки зрения твоей роли. 1–3 предложения.";

            return llmService.generateResponse(context);
        } catch (Exception e) {
            e.printStackTrace();
            return "Не удалось получить объяснение от LLM.";
        }
    }

    /**
     * Упрощённый снимок состояния игры для записи в training_data
     * и для контекста LLM.
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
