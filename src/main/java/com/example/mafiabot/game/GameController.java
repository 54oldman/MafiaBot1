package com.example.mafiabot.game;

import com.example.mafiabot.db.GameDao;
import com.example.mafiabot.db.GamePlayerDao;
import com.example.mafiabot.db.MoveDao;
import com.example.mafiabot.db.TrainingDataDao;
import com.example.mafiabot.db.UserDao;

import java.sql.SQLException;
import java.util.*;

public class GameController {

    private final Map<Long, GameSession> sessions = new HashMap<>();

    private final UserDao userDao;
    private final GameDao gameDao;
    private final GamePlayerDao gamePlayerDao;
    private final MoveDao moveDao;
    private final TrainingDataDao trainingDataDao;
    private final AIPlayer aiPlayer;

    private final Random random = new Random();

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

    // ===== /join, /addbots, /startgame — как раньше =====

    public String handleJoin(long chatId, long telegramUserId, String username) throws Exception {
        GameSession session = getOrCreateSession(chatId);
        long dbUserId = userDao.getOrCreateUser(telegramUserId, username);
        session.putUserMapping(telegramUserId, dbUserId);

        session.getManager().joinPlayer(telegramUserId, username);
        return "Ты присоединился к игре как " + username;
    }

    public String handleAddBots(long chatId, int count) throws Exception {
        if (count <= 0) {
            return "Число ботов должно быть положительным.";
        }
        GameSession session = getOrCreateSession(chatId);
        GameManager gm = session.getManager();

        int existingBots = 0;
        for (Player p : gm.getPlayers()) {
            if (p.getChatId() < 0) {
                existingBots++;
            }
        }

        for (int i = 0; i < count; i++) {
            long botTelegramId = -1000L - existingBots - i;
            String botName = "BOT_" + (existingBots + i + 1);
            long dbUserId = userDao.getOrCreateUser(botTelegramId, botName);
            session.putUserMapping(botTelegramId, dbUserId);
            gm.joinPlayer(botTelegramId, botName);
        }

        return "Добавлено ботов: " + count +
                ". Сейчас игроков (включая ботов): " + gm.getPlayers().size();
    }

    public String handleStartGame(long chatId) throws Exception {
        GameSession session = getOrCreateSession(chatId);
        GameManager gm = session.getManager();

        int totalPlayers = gm.getPlayers().size();
        if (totalPlayers != 7) {
            return "Для этой версии игры нужно ровно 7 игроков (2 мафии, 3 мирных, 1 шериф, 1 доктор).\n" +
                    "Сейчас игроков: " + totalPlayers + ".\n" +
                    "Используй /addbots N, чтобы довести число до 7.";
        }

        gm.startGame();

        for (Player p : gm.getPlayers()) {
            Long dbUserId = session.getDbUserId(p.getChatId());
            if (dbUserId != null) {
                boolean isAi = p.getChatId() < 0;
                gamePlayerDao.addPlayer(
                        session.getGameId(),
                        dbUserId,
                        p.getRole() != null ? p.getRole().name() : null,
                        isAi
                );
            }
        }

        return "Игра началась! Роли разданы: в партии есть 2 мафии, 3 мирных, шериф и доктор.\n" +
                "Сейчас НОЧЬ. Все роли ходят через /ai_move.";
    }

    // ===== НОЧЬ: /ai_move — как раньше =====

    public String handleAiMove(long chatId) throws Exception {
        GameSession session = getOrCreateSession(chatId);
        GameManager gm = session.getManager();

        if (gm.isFinished()) {
            return "Игра уже окончена. Используй /newgame, чтобы начать новую партию.";
        }
        if (gm.getPhase() != Phase.NIGHT) {
            return "Сейчас не ночь (фаза: " + gm.getPhase() +
                    "). Ночной ход доступен только ночью.";
        }

        List<Player> mafias = new ArrayList<>();
        Player doctor = null;
        Player sheriff = null;

        for (Player p : gm.getPlayers()) {
            if (!p.isAlive() || p.getRole() == null) continue;

            switch (p.getRole()) {
                case MAFIA -> mafias.add(p);
                case DOCTOR -> doctor = p;
                case SHERIFF -> sheriff = p;
                default -> {}
            }
        }

        StringBuilder out = new StringBuilder();

        AIPlayer.MafiaDecision decision = null;
        if (!mafias.isEmpty()) {
            Player actingMafia = null;
            for (Player m : mafias) {
                if (m.getChatId() < 0) {
                    actingMafia = m;
                    break;
                }
            }
            if (actingMafia == null) {
                actingMafia = mafias.get(0);
            }

            Long mafiaDbId = session.getDbUserId(actingMafia.getChatId());
            if (mafiaDbId == null) {
                mafiaDbId = userDao.getOrCreateUser(actingMafia.getChatId(), actingMafia.getUsername());
                session.putUserMapping(actingMafia.getChatId(), mafiaDbId);
            }

            decision = aiPlayer.decideMafiaTarget(session, actingMafia.getChatId(), mafiaDbId);

            if (decision != null) {
                out.append("Мафия решила убить игрока: ")
                        .append(decision.targetUsername)
                        .append(".\n")
                        .append("Стратегия: ").append(decision.strategicReason).append("\n")
                        .append("Объяснение ИИ: ").append(decision.explanation).append("\n");
            } else {
                out.append("Мафия этой ночью не смогла выбрать цель.\n");
            }
        } else {
            out.append("Все мафии мертвы — ночью никто не атакует.\n");
        }

        Long doctorTargetId = null;
        if (doctor != null && doctor.isAlive()) {
            List<Player> candidates = new ArrayList<>();
            for (Player p : gm.getPlayers()) {
                if (p.isAlive()) {
                    candidates.add(p);
                }
            }
            if (!candidates.isEmpty()) {
                Player dt = candidates.get(random.nextInt(candidates.size()));
                doctorTargetId = dt.getChatId();

                Long docDbId = session.getDbUserId(doctor.getChatId());
                if (docDbId == null) {
                    docDbId = userDao.getOrCreateUser(doctor.getChatId(), doctor.getUsername());
                    session.putUserMapping(doctor.getChatId(), docDbId);
                }
                moveDao.insertMove(
                        session.getGameId(),
                        docDbId,
                        "doctor_heal",
                        "targetTelegramId=" + doctorTargetId
                );
            }
            out.append("Доктор попытался спасти одного из игроков.\n");
        }

        if (sheriff != null && sheriff.isAlive()) {
            List<Player> candidates = new ArrayList<>();
            for (Player p : gm.getPlayers()) {
                if (!p.isAlive()) continue;
                if (p.getChatId() == sheriff.getChatId()) continue;
                candidates.add(p);
            }
            if (!candidates.isEmpty()) {
                Player checked = candidates.get(random.nextInt(candidates.size()));
                Long sherDbId = session.getDbUserId(sheriff.getChatId());
                if (sherDbId == null) {
                    sherDbId = userDao.getOrCreateUser(sheriff.getChatId(), sheriff.getUsername());
                    session.putUserMapping(sheriff.getChatId(), sherDbId);
                }
                moveDao.insertMove(
                        session.getGameId(),
                        sherDbId,
                        "sheriff_check",
                        "targetTelegramId=" + checked.getChatId()
                );
            }
            out.append("Шериф этой ночью кого-то проверил.\n");
        }

        boolean someoneDied = false;
        String deadName = null;

        if (decision != null && decision.targetTelegramId != 0L) {
            long targetId = decision.targetTelegramId;
            if (doctorTargetId != null && doctorTargetId == targetId) {
                out.append("Доктор спас предполагаемую жертву — этой ночью никто не погиб.\n");
            } else {
                Player victim = findPlayerByTelegramId(gm, targetId);
                if (victim != null && victim.isAlive()) {
                    gm.kill(targetId);
                    someoneDied = true;
                    deadName = victim.getUsername();
                }
            }
        }

        if (someoneDied) {
            out.append("Ночью был убит игрок ").append(deadName).append(".\n");
        } else if (decision != null) {
            out.append("В итоге ночью никто не погиб.\n");
        }

        String winner = gm.checkWinner();
        if (winner != null) {
            gameDao.finishGame(session.getGameId(), winner);
            String outcome = winner.equals("MAFIA") ? "win" : "lose";
            trainingDataDao.updateOutcomeForGame(session.getGameId(), outcome);

            out.append("Игра окончена! Победитель: ")
                    .append(winner.equals("MAFIA") ? "мафия" : "мирные жители")
                    .append(" (outcome=").append(outcome).append(")");
        } else {
            gm.setPhase(Phase.DAY);
            out.append("Наступает ДЕНЬ. Обсуждайте и используйте /vote @ник для голосования за казнь.\n")
                    .append("После голосования ведущий должен использовать /endday, чтобы подвести итог дня.");
        }

        return out.toString().trim();
    }

    private Player findPlayerByTelegramId(GameManager gm, long telegramId) {
        for (Player p : gm.getPlayers()) {
            if (p.getChatId() == telegramId) {
                return p;
            }
        }
        return null;
    }

    /**
     * Автоматические голоса всех ботов днём + объяснения ИИ.
     * Возвращаем текст, который потом будет выведен в ответе /endday.
     */
    private String autoBotVotes(GameSession session, GameManager gm) throws Exception {
        StringBuilder info = new StringBuilder();
        Map<Long, Long> currentVotes = gm.getDayVotesSnapshot();

        for (Player bot : gm.getPlayers()) {
            if (!bot.isAlive()) continue;
            if (bot.getChatId() >= 0) continue;             // живой человек
            if (currentVotes.containsKey(bot.getChatId())) continue; // бот уже голосовал

            List<Player> candidates = new ArrayList<>();
            for (Player p : gm.getPlayers()) {
                if (!p.isAlive()) continue;
                if (p.getChatId() == bot.getChatId()) continue;

                if (bot.getRole() == Role.MAFIA) {
                    if (p.getRole() != Role.MAFIA) {
                        candidates.add(p);
                    }
                } else {
                    if (p.getRole() == Role.MAFIA) {
                        candidates.add(p);
                    }
                }
            }

            if (candidates.isEmpty()) {
                for (Player p : gm.getPlayers()) {
                    if (!p.isAlive()) continue;
                    if (p.getChatId() == bot.getChatId()) continue;
                    candidates.add(p);
                }
            }

            if (candidates.isEmpty()) continue;

            Player target = candidates.get(random.nextInt(candidates.size()));

            gm.castDayVote(bot.getChatId(), target.getChatId());

            Long botDbId = session.getDbUserId(bot.getChatId());
            if (botDbId != null) {
                moveDao.insertMove(
                        session.getGameId(),
                        botDbId,
                        "bot_vote",
                        "targetTelegramId=" + target.getChatId()
                );
            }

            // Объяснение от LLM
            String explanation = aiPlayer.explainDayVote(gm, bot, target);

            info.append(bot.getUsername())
                    .append(" голосует против ")
                    .append(target.getUsername())
                    .append(".\n")
                    .append("Объяснение ИИ: ")
                    .append(explanation)
                    .append("\n\n");
        }

        return info.toString();
    }

    // ===== /vote и /accuse (алиас) =====

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

        String base = gm.castDayVote(voterTelegramId, target.getChatId());
        moveDao.insertMove(
                session.getGameId(),
                voterDbId,
                "human_vote",
                "targetTelegramId=" + target.getChatId()
        );

        Map<Long, Long> votes = gm.getDayVotesSnapshot();
        int voters = 0;
        for (Map.Entry<Long, Long> e : votes.entrySet()) {
            Player voter = findPlayerByTelegramId(gm, e.getKey());
            if (voter != null && voter.isAlive()) {
                voters++;
            }
        }
        int alive = gm.getAliveCount();

        return base + "\nСейчас проголосовали " + voters + " из " + alive +
                " живых игроков.\n" +
                "Когда все (или ведущий решит), используй /endday для подсчёта голосов.";
    }

    public String handleHumanAccuse(long chatId,
                                    long accuserTelegramId,
                                    String targetUsername) throws Exception {
        return handleVote(chatId, accuserTelegramId, targetUsername);
    }

    // ===== /endday — теперь с объяснениями ботов =====

    public String handleEndDay(long chatId) throws Exception {
        GameSession session = getOrCreateSession(chatId);
        GameManager gm = session.getManager();

        if (gm.isFinished()) {
            return "Игра уже окончена. Используй /newgame, чтобы начать новую партию.";
        }
        if (gm.getPhase() != Phase.DAY) {
            return "Сейчас не день (фаза: " + gm.getPhase() + "). /endday доступен только днём.";
        }

        StringBuilder sb = new StringBuilder();

        // сперва даём ботам проголосовать и получить объяснения
        String botInfo = autoBotVotes(session, gm);
        if (!botInfo.isBlank()) {
            sb.append("Голоса ботов:\n")
                    .append(botInfo);
        }

        Map<Long, Long> votes = gm.getDayVotesSnapshot();
        if (votes.isEmpty()) {
            gm.setPhase(Phase.NIGHT);
            sb.append("Никто не голосовал. Никто не казнён.\n")
                    .append("Наступает НОЧЬ. Ночной ход выполняется через /ai_move.");
            return sb.toString();
        }

        Map<Long, Integer> counts = new HashMap<>();
        for (Map.Entry<Long, Long> e : votes.entrySet()) {
            Player voter = findPlayerByTelegramId(gm, e.getKey());
            if (voter == null || !voter.isAlive()) continue;
            counts.merge(e.getValue(), 1, Integer::sum);
        }

        long bestTargetId = -1;
        int bestCount = 0;
        boolean tie = false;
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                bestTargetId = e.getKey();
                tie = false;
            } else if (e.getValue() == bestCount) {
                tie = true;
            }
        }

        if (bestTargetId == -1 || bestCount == 0 || tie) {
            gm.clearDayVotes();
            gm.setPhase(Phase.NIGHT);
            sb.append("Голоса разделились или ни один игрок не набрал преимущества. Никто не был казнён.\n")
                    .append("Наступает НОЧЬ. Ночной ход выполняется через /ai_move.");
            return sb.toString();
        }

        Player lynch = findPlayerByTelegramId(gm, bestTargetId);
        if (lynch == null || !lynch.isAlive()) {
            gm.clearDayVotes();
            gm.setPhase(Phase.NIGHT);
            sb.append("Произошла ошибка при подсчёте голосов. Никто не был казнён.\n")
                    .append("Наступает НОЧЬ. Ночной ход выполняется через /ai_move.");
            return sb.toString();
        }

        Long execVoterDbId = null;
        for (Map.Entry<Long, Long> e : votes.entrySet()) {
            if (e.getValue().equals(bestTargetId)) {
                execVoterDbId = session.getDbUserId(e.getKey());
                break;
            }
        }

        gm.kill(lynch.getChatId());
        if (execVoterDbId != null) {
            moveDao.insertMove(
                    session.getGameId(),
                    execVoterDbId,
                    "day_execution",
                    "targetTelegramId=" + lynch.getChatId()
            );
        }

        gm.clearDayVotes();

        sb.append("По результатам голосования казнён игрок ")
                .append(lynch.getUsername())
                .append(".\n");

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
            sb.append("Наступает НОЧЬ. Ночной ход выполняется через /ai_move.");
        }

        return sb.toString();
    }

    // ===== /newgame и getSession =====

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

        return "Создана новая игра. Набери ровно 7 игроков (люди + боты) через /join и /addbots N, затем /startgame.";
    }

    public GameSession getSession(long chatId) {
        return sessions.get(chatId);
    }
}
