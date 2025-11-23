package com.example.mafiabot.game;


import com.example.mafiabot.db.*;
import com.example.mafiabot.game.AIPlayer;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class GameController {
    private final Map<Long, GameSession> sessions = new HashMap<>();

    private final AIPlayer aiPlayer;

    private final UserDao userDao;
    private final GameDao gameDao;
    private final GamePlayerDao gamePlayerDao;
    private final MoveDao moveDao;

    public GameController(UserDao userDao,
                          GameDao gameDao,
                          GamePlayerDao gamePlayerDao,
                          MoveDao moveDao,
                          AIPlayer aiPlayer) {
        this.userDao = userDao;
        this.gameDao = gameDao;
        this.gamePlayerDao = gamePlayerDao;
        this.moveDao = moveDao;
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

    public String handleStartGame(long chatId) throws Exception {
        GameSession session = getOrCreateSession(chatId);
        GameManager gm = session.getManager();
        gm.startGame();

        // записываем игроков в game_players
        for (Player p : gm.getPlayers()) {
            Long dbUserId = session.getDbUserId(p.getChatId());
            if (dbUserId != null) {
                gamePlayerDao.addPlayer(session.getGameId(), dbUserId,
                        p.getRole() != null ? p.getRole().name() : null,
                        false);
            }
        }
        return "Игра началась! Роли розданы.";
    }

    public String handleAccuse(long chatId, long accuserTelegramId, long targetTelegramId) throws Exception {
        GameSession session = getOrCreateSession(chatId);
        GameManager gm = session.getManager();

        String text = gm.accuse(accuserTelegramId, targetTelegramId);

        Long accuserDbId = session.getDbUserId(accuserTelegramId);
        if (accuserDbId != null) {
            moveDao.insertMove(
                    session.getGameId(),
                    accuserDbId,
                    "accuse",
                    "targetTelegramId=" + targetTelegramId
            );
        }
        return text;
    }
    public String handleAiMove(long chatId) throws Exception {
        GameSession session = getOrCreateSession(chatId);
        GameManager gm = session.getManager();

        long aiTelegramId = -1L;        // виртуальный ID ИИ
        String aiUsername  = "AI_MAFIA";

        // 1. зарегистрировать ИИ как пользователя/игрока, если его ещё нет
        Long aiDbUserId = session.getDbUserId(aiTelegramId);
        if (aiDbUserId == null) {
            // user
            aiDbUserId = userDao.getOrCreateUser(aiTelegramId, aiUsername);
            session.putUserMapping(aiTelegramId, aiDbUserId);

            // game manager
            boolean exists = false;
            for (Player p : gm.getPlayers()) {
                if (p.getChatId() == aiTelegramId) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                gm.joinPlayer(aiTelegramId, aiUsername);
                // game_players с флагом is_ai = true
                gamePlayerDao.addPlayer(session.getGameId(), aiDbUserId, "MAFIA", true);
            }
        }

        // 2. просим AIPlayer сделать ход
        return aiPlayer.makeAccuseMove(session, aiTelegramId, aiDbUserId);
    }



    public GameSession getSession(long chatId) {
        return sessions.get(chatId);
    }
}
