package com.example.mafiabot.game;

import com.example.mafiabot.db.GameDao;
import com.example.mafiabot.db.GamePlayerDao;
import com.example.mafiabot.db.MoveDao;
import com.example.mafiabot.db.TrainingDataDao;
import com.example.mafiabot.db.UserDao;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class  GameController {

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

    /** Получаем сессию для чата, если нет — создаём новую игру. */
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

    /** /join */
    public String handleJoin(long chatId, long telegramUserId, String username) throws Exception {
        GameSession session = getOrCreateSession(chatId);
        long dbUserId = userDao.getOrCreateUser(telegramUserId, username);
        session.putUserMapping(telegramUserId, dbUserId);

        session.getManager().joinPlayer(telegramUserId, username);
        return "Ты присоединился к игре как " + username;
    }

    /** /startgame — старт, раздача ролей, первая фаза: НОЧЬ */
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

        // ПРОВЕРКА: минимум 3 игрока (2 человека + ИИ)
        int totalPlayers = gm.getPlayers().size();
        if (totalPlayers < 3) {
            return "Мало игроков для старта. Нужно минимум 2 человека + ИИ (сейчас: "
                    + (totalPlayers - 1) + " человек).";
        }

        // старт игры и распределение ролей (фаза NIGHT)
        gm.startGame();

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


    /** Ход ИИ (мафии). Разрешён только ночью. */
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
            text += "\nНаступает ДЕНЬ. Обсуждайте и используйте /vote @ник для голосования за казнь.";
        }

        return text;
    }

    /** Вспомогательный поиск игрока по telegramId. */
    private Player findPlayerByTelegramId(GameManager gm, long telegramId) {
        for (Player p : gm.getPlayers()) {
            if (p.getChatId() == telegramId) {
                return p;
            }
        }
        return null;
    }

    /** Голосование днём: /vote или /accuse */
    public String handleVote(long chatId,
                             long voterTelegramId,
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

        Long voterDbId = session.getDbUserId(voterTelegramId);
        if (voterDbId == null) {
            return "Сначала присоединись к игре через /join.";
        }

        // ищем цель по нику
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

        // регистрируем голос
        String base = gm.castDayVote(voterTelegramId, target.getChatId());

        // логируем голос в moves
        moveDao.insertMove(
                session.getGameId(),
                voterDbId,
                "human_vote",
                "targetTelegramId=" + target.getChatId()
        );

        // считаем, сколько голосов за каждого живого игрока
        Map<Long, Long> votes = gm.getDayVotesSnapshot();
        Map<Long, Integer> counts = new HashMap<>();

        for (Map.Entry<Long, Long> e : votes.entrySet()) {
            Player voter = findPlayerByTelegramId(gm, e.getKey());
            if (voter == null || !voter.isAlive()) continue;
            counts.merge(e.getValue(), 1, Integer::sum);
        }

        int alive = gm.getAliveCount();
        int needed = alive / 2 + 1;

        long chosenTargetId = -1;
        int maxVotes = 0;
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            if (e.getValue() > maxVotes) {
                maxVotes = e.getValue();
                chosenTargetId = e.getKey();
            }
        }

        // если большинство ещё не набрано — просто показываем прогресс
        if (chosenTargetId == -1 || maxVotes < needed) {
            int currentForTarget = counts.getOrDefault(target.getChatId(), 0);
            return base + "\nТеперь за " + target.getUsername() + " голосов: "
                    + currentForTarget + " из " + needed + " необходимых для казни.";
        }

        // есть большинство — казнь
        Player lynch = findPlayerByTelegramId(gm, chosenTargetId);
        if (lynch == null) {
            return base + "\nПроизошла ошибка при определении цели казни.";
        }

        String accuseText = gm.accuse(voterTelegramId, lynch.getChatId());
        gm.kill(lynch.getChatId());

        // логируем саму казнь отдельным ходом
        moveDao.insertMove(
                session.getGameId(),
                voterDbId,
                "day_execution",
                "targetTelegramId=" + lynch.getChatId()
        );

        gm.clearDayVotes(); // новый цикл — голоса обнуляем

        StringBuilder sb = new StringBuilder();
        sb.append(base).append("\n");
        sb.append("Голоса подсчитаны. Большинство против ").append(lynch.getUsername()).append(".\n");
        sb.append("Результат: ").append(accuseText)
                .append(". Игрок ").append(lynch.getUsername()).append(" казнён днём.\n");

        // проверяем победителя
        String winner = gm.checkWinner();
        if (winner != null) {
            gameDao.finishGame(session.getGameId(), winner);
            String outcome = winner.equals("MAFIA") ? "win" : "lose";
            trainingDataDao.updateOutcomeForGame(session.getGameId(), outcome);

            sb.append("Игра окончена! Победитель: ")
                    .append(winner.equals("MAFIA") ? "мафия" : "мирные жители")
                    .append(" (outcome=").append(outcome).append(")");
        } else {
            gm.setPhase(Phase.NIGHT);
            sb.append("Наступает НОЧЬ. Мафия ходит через /ai_move.");
        }

        return sb.toString();
    }

    /** Старое /accuse можно оставить как алиас к голосованию. */
    public String handleHumanAccuse(long chatId,
                                    long accuserTelegramId,
                                    String targetUsername) throws Exception {
        return handleVote(chatId, accuserTelegramId, targetUsername);
    }

    /** /newgame — новая игра в этом чате. */
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
