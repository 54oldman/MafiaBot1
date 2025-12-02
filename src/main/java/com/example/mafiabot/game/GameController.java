package com.example.mafiabot.game;

import com.example.mafiabot.db.GameDao;
import com.example.mafiabot.db.GamePlayerDao;
import com.example.mafiabot.db.MoveDao;
import com.example.mafiabot.db.TrainingDataDao;
import com.example.mafiabot.db.UserDao;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class GameController {
    private final Map<Long, GameSession> sessions = new HashMap<>();

    private final UserDao userDao;
    private final GameDao gameDao;
    private final GamePlayerDao gamePlayerDao;
    private final MoveDao moveDao;
    private final TrainingDataDao trainingDataDao;
    private final AIPlayer aiPlayer;

    public GameController(UserDao userDao,
                          GameDao gameDao,
                          GamePlayerDao gamePlayerDao,
                          MoveDao moveDao,
                          TrainingDataDao trainingDataDao,
                          AIPlayer aiPlayer) {
        this.userDao = userDao;
        this.gameDao = gameDao;
        this.gamePlayerDao = gamePlayerDao;
        this.moveDao = moveDao;
        this.trainingDataDao = trainingDataDao;
        this.aiPlayer = aiPlayer;
    }

    private GameSession getOrCreateSession(long chatId) throws SQLException {
        GameSession s = sessions.get(chatId);
        if (s == null) {
            long gameId = gameDao.createGame(chatId);
            GameManager gm = new GameManager();
            s = new GameSession(chatId, gameId, gm);
            sessions.put(chatId, s);
        }
        return s;
    }

    public String handleJoin(long chatId, long telegramUserId, String username) throws Exception {
        GameSession session = getOrCreateSession(chatId);
        long dbUserId = userDao.getOrCreateUser(telegramUserId, username);
        session.putUserMapping(telegramUserId, dbUserId);

        session.getManager().joinPlayer(telegramUserId, username);
        return "Ты присоединился к игре как " + username;
    }

    /** Старт: создаём (если надо) ИИ и запускаем игру. Первая фаза — НОЧЬ. */
    public String handleStartGame(long chatId) throws Exception {
        GameSession session = getOrCreateSession(chatId);
        GameManager gm = session.getManager();

        // добавляем ИИ-игрока, если его нет
        long aiTelegramId = -1L;
        String aiUsername = "AI_MAFIA";
        Long aiDbUserId = session.getDbUserId(aiTelegramId);
        if (aiDbUserId == null) {
            aiDbUserId = userDao.getOrCreateUser(aiTelegramId, aiUsername);
            session.putUserMapping(aiTelegramId, aiDbUserId);
            gm.joinPlayer(aiTelegramId, aiUsername);
        }

        // старт игры и распределение ролей
        gm.startGame(); // внутри ставится phase = NIGHT

        // подстраховка по ролям
        for (Player p : gm.getPlayers()) {
            if (p.getRole() == null) {
                if (p.getUsername().equals(aiUsername)) {
                    p.setRole(Role.MAFIA);
                } else {
                    p.setRole(Role.TOWN);
                }
            }
        }

        // сохраняем игроков в game_players
        for (Player p : gm.getPlayers()) {
            Long dbUserId = session.getDbUserId(p.getChatId());
            if (dbUserId != null) {
                boolean isAi = p.getUsername().equals(aiUsername);
                gamePlayerDao.addPlayer(
                        session.getGameId(),
                        dbUserId,
                        p.getRole() != null ? p.getRole().name() : null,
                        isAi
                );
            }
        }

        return "Игра началась! Сейчас НОЧЬ. Мафия ходит командой /ai_move.";
    }

    /** Ход ИИ (мафия ходит только ночью) */
    public String handleAiMove(long chatId) throws Exception {
        GameSession session = getOrCreateSession(chatId);
        GameManager gm = session.getManager();

        if (gm.isFinished()) {
            return "Игра уже окончена. Используй /newgame, чтобы начать новую партию.";
        }
        if (gm.getPhase() != Phase.NIGHT) {
            return "Сейчас не ночь (фаза: " + gm.getPhase() +
                    "). Ход мафии доступен только ночью.";
        }

        long aiTelegramId = -1L;
        String aiUsername = "AI_MAFIA";

        Long aiDbUserId = session.getDbUserId(aiTelegramId);
        if (aiDbUserId == null) {
            aiDbUserId = userDao.getOrCreateUser(aiTelegramId, aiUsername);
            session.putUserMapping(aiTelegramId, aiDbUserId);
            gm.joinPlayer(aiTelegramId, aiUsername);
        }

        String text = aiPlayer.makeAccuseMove(session, aiTelegramId, aiDbUserId);

        // проверяем победителя
        String winner = gm.checkWinner();
        if (winner != null) {
            gameDao.finishGame(session.getGameId(), winner);
            String outcome = winner.equals("MAFIA") ? "win" : "lose";
            trainingDataDao.updateOutcomeForGame(session.getGameId(), outcome);

            text += "\nИгра окончена! Победитель: " +
                    (winner.equals("MAFIA") ? "мафия" : "мирные жители") +
                    " (outcome=" + outcome + ")";
        } else {
            gm.setPhase(Phase.DAY);
            text += "\nНаступает ДЕНЬ. Обсуждайте и используйте /accuse @ник для казни.";
        }

        return text;
    }

    /** Игрок обвиняет другого (казнь днём) */
    public String handleHumanAccuse(long chatId,
                                    long accuserTelegramId,
                                    String targetUsername) throws Exception {
        GameSession session = getOrCreateSession(chatId);
        GameManager gm = session.getManager();

        if (gm.isFinished()) {
            return "Игра уже окончена. Используй /newgame, чтобы начать новую партию.";
        }
        if (gm.getPhase() != Phase.DAY) {
            return "Сейчас не день (фаза: " + gm.getPhase() +
                    "). Голосовать можно только днём.";
        }

        Long accuserDbId = session.getDbUserId(accuserTelegramId);
        if (accuserDbId == null) {
            return "Сначала присоединись к игре через /join.";
        }

        Player target = null;
        for (Player p : gm.getPlayers()) {
            if (p.getUsername().equalsIgnoreCase(targetUsername)) {
                target = p;
                break;
            }
        }
        if (target == null) {
            return "Игрок с ником " + targetUsername + " не найден среди участников.";
        }
        if (!target.isAlive()) {
            return "Игрок " + targetUsername + " уже выбыл из игры.";
        }

        String text = gm.accuse(accuserTelegramId, target.getChatId());
        gm.kill(target.getChatId());

        moveDao.insertMove(
                session.getGameId(),
                accuserDbId,
                "human_accuse",
                "targetTelegramId=" + target.getChatId()
        );

        String winner = gm.checkWinner();
        if (winner != null) {
            gameDao.finishGame(session.getGameId(), winner);
            String outcome = winner.equals("MAFIA") ? "win" : "lose";
            trainingDataDao.updateOutcomeForGame(session.getGameId(), outcome);

            text += "\nИгра окончена! Победитель: " +
                    (winner.equals("MAFIA") ? "мафия" : "мирные жители") +
                    " (outcome=" + outcome + ")";
        } else {
            gm.setPhase(Phase.NIGHT);
            text += "\nНаступает НОЧЬ. Мафия ходит через /ai_move.";
        }

        return text;
    }

    /** Начать новую игру в этом чате */
    public String handleNewGame(long chatId) throws Exception {
        GameSession old = sessions.get(chatId);
        if (old != null && !old.getManager().isFinished()) {
            gameDao.finishGame(old.getGameId(), "unknown");
            trainingDataDao.updateOutcomeForGame(old.getGameId(), "unknown");
        }

        long newGameId = gameDao.createGame(chatId);
        GameManager gm = new GameManager();
        GameSession session = new GameSession(chatId, newGameId, gm);
        sessions.put(chatId, session);

        return "Создана новая игра. Используй /join, затем /startgame.";
    }

    public GameSession getSession(long chatId) {
        return sessions.get(chatId);
    }
}
