package com.example.mafiabot.game;

import com.example.mafiabot.db.MoveDao;
import com.example.mafiabot.db.TrainingDataDao;
import com.google.gson.Gson;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AIPlayer {
    private final MoveDao moveDao;
    private final TrainingDataDao trainingDao;
    private final Random random = new Random();
    private final Gson gson = new Gson();

    public AIPlayer(MoveDao moveDao, TrainingDataDao trainingDao) {
        this.moveDao = moveDao;
        this.trainingDao = trainingDao;
    }

    /**
     * Простейший ИИ: выбирает жертву среди живых людей и "обвиняет" её.
     *
     * @param session  текущая игровая сессия
     * @param aiTelegramId telegram_id ИИ (мы будем считать, что это отдельный "виртуальный" игрок)
     * @param aiDbUserId   id этого ИИ в таблице users
     * @return текст, который можно вывести в чат
     */
    public String makeAccuseMove(GameSession session,
                                 long aiTelegramId,
                                 long aiDbUserId) throws Exception {

        GameManager gm = session.getManager();

        // 1. выбираем цель
        Player self = null;
        List<Player> candidates = new ArrayList<>();
        for (Player p : gm.getPlayers()) {
            if (!p.isAlive()) continue;
            if (p.getChatId() == aiTelegramId) {
                self = p;
                continue;
            }
            candidates.add(p);
        }
        if (candidates.isEmpty()) {
            return "ИИ пока не может сделать ход.";
        }


        Player target = candidates.get(random.nextInt(candidates.size()));

        // 2. Сохраняем ход в GameManager (для логики игры)
        String resultText = gm.accuse(aiTelegramId, target.getChatId());

        // 3. Записываем ход в таблицу moves
        long moveId = moveDao.insertMove(
                session.getGameId(),
                aiDbUserId,
                "ai_accuse",
                "targetTelegramId=" + target.getChatId()
        );

        // 4. Формируем snapshot состояния (очень простой JSON с кол-вом игроков и именами)
        GameStateSnapshot snapshot = GameStateSnapshot.fromManager(gm, "MAFIA_AI");
        String snapshotJson = gson.toJson(snapshot);

        // 5. Записываем строку в training_data
        trainingDao.insertTrainingRow(
                session.getGameId(),
                moveId,
                "mafia_ai",
                snapshotJson,
                "accuse:" + target.getUsername(),
                "unknown"  // исход пока неизвестен, можно обновлять в конце игры
        );

        return "ИИ (мафия) делает ход: " + resultText;
    }

    /** Внутренний класс для сериализации состояния игры */
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
