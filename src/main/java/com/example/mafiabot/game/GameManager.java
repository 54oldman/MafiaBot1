package com.example.mafiabot.game;

import java.util.*;

/**
 * Управляет одной игрой: игроки, роли, фазы (день/ночь), победитель и т.д.
 */
public class GameManager {

    // telegramId -> Player
    private final Map<Long, Player> players = new LinkedHashMap<>();
    private final List<Round> rounds = new ArrayList<>();

    // Голоса днём: voterId -> targetId
    private final Map<Long, Long> dayVotes = new HashMap<>();

    private boolean started = false;
    private boolean finished = false;
    private String winner = null;

    private Phase phase = Phase.LOBBY;

    private final Random rnd = new Random();

    /** Игрок входит в лобби (пока игра не началась). */
    public synchronized void joinPlayer(long chatId, String username) {
        if (started) {
            // Игроки после старта не добавляются
            return;
        }
        players.putIfAbsent(chatId, new Player(chatId, username));
    }

    /** Старт игры + раздача ролей. Первая фаза — НОЧЬ. */
    public synchronized void startGame() {
        if (started) return;
        started = true;
        assignRoles();
        phase = Phase.NIGHT;
    }

    /** Случайно распределяем роли между игроками. */
    private void assignRoles() {
        List<Player> list = new ArrayList<>(players.values());
        if (list.isEmpty()) return;

        Collections.shuffle(list, rnd);
        int n = list.size();

        if (n == 1) {
            list.get(0).setRole(Role.TOWN);
            return;
        }

        // Простейшее правило: минимум 1 мафия, примерно 1/4 от игроков
        int mafiaCount = Math.max(1, n / 4);
        int index = 0;

        // Мафия
        for (int i = 0; i < mafiaCount && index < n; i++, index++) {
            list.get(index).setRole(Role.MAFIA);
        }

        // Один шериф (если остались игроки)
        if (index < n) {
            list.get(index).setRole(Role.SHERIFF);
            index++;
        }

        // Остальные — мирные
        while (index < n) {
            list.get(index).setRole(Role.TOWN);
            index++;
        }
    }

    /** Простая запись обвинения в историю раундов. */
    public synchronized String accuse(long accuserId, long targetId) {
        Player acc = players.get(accuserId);
        Player tgt = players.get(targetId);
        if (acc == null || tgt == null) {
            return "Игрок не найден.";
        }

        Map<String, String> move =
                Map.of(acc.getUsername(), "accuse " + tgt.getUsername());
        rounds.add(new Round(rounds.size() + 1, move));

        return acc.getUsername() + " подозревает " + tgt.getUsername();
    }

    /** Убить игрока (пометить как выбывшего). */
    public synchronized void kill(long targetId) {
        Player tgt = players.get(targetId);
        if (tgt != null) {
            tgt.setAlive(false);
        }
    }

    /** Количество живых игроков. */
    public synchronized int getAliveCount() {
        int c = 0;
        for (Player p : players.values()) {
            if (p.isAlive()) c++;
        }
        return c;
    }

    /**
     * Зарегистрировать голос игрока днём.
     * voterId -> targetId
     */
    public synchronized String castDayVote(long voterId, long targetId) {
        Player voter = players.get(voterId);
        Player target = players.get(targetId);

        if (voter == null || target == null) {
            return "Игрок не найден.";
        }
        if (!voter.isAlive()) {
            return "Мёртвые не голосуют.";
        }
        if (!target.isAlive()) {
            return "Нельзя голосовать за уже выбывшего игрока.";
        }

        dayVotes.put(voterId, targetId);
        return voter.getUsername() + " голосует против " + target.getUsername();
    }

    /** Снимок голосов дня (voterId -> targetId). */
    public synchronized Map<Long, Long> getDayVotesSnapshot() {
        return new HashMap<>(dayVotes);
    }

    /** Очистить голоса после завершения дня. */
    public synchronized void clearDayVotes() {
        dayVotes.clear();
    }

    /**
     * Проверяем, не закончилась ли игра.
     * @return null, если игра продолжается;
     *         "MAFIA" или "TOWN", если есть победитель.
     */
    public synchronized String checkWinner() {
        if (!started) return null;
        if (finished) return winner;

        int mafiaAlive = 0;
        int townAlive = 0;

        for (Player p : players.values()) {
            if (!p.isAlive() || p.getRole() == null) continue;

            if (p.getRole() == Role.MAFIA) {
                mafiaAlive++;
            } else {
                townAlive++;
            }
        }

        if (mafiaAlive == 0 && (mafiaAlive + townAlive) > 0) {
            finished = true;
            winner = "TOWN";
            phase = Phase.FINISHED;
        } else if (mafiaAlive >= townAlive && mafiaAlive > 0) {
            finished = true;
            winner = "MAFIA";
            phase = Phase.FINISHED;
        }

        return winner;
    }

    // ==== Геттеры/сеттеры ====

    public synchronized Collection<Player> getPlayers() {
        return players.values();
    }

    public synchronized List<Round> getRounds() {
        return rounds;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    public synchronized String getWinner() {
        return winner;
    }

    public synchronized Phase getPhase() {
        return phase;
    }

    public synchronized void setPhase(Phase phase) {
        this.phase = phase;
    }
}
