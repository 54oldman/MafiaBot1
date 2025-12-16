package com.example.mafiabot.game;

import java.util.*;

/**
 * Управляет одной игрой: игроки, роли, фазы (день/ночь), победитель, голоса.
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
            return; // после старта не принимаем новых
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

    /**
     * Распределяем роли:
     *  - 2 мафии
     *  - 1 шериф
     *  - 1 доктор
     *  - остальные (в нашем режиме это ещё 3) — мирные
     *
     * Ожидается, что игроков ровно 7.
     */
    private void assignRoles() {
        List<Player> list = new ArrayList<>(players.values());
        int n = list.size();
        if (n < 4) {
            // fallback: если вдруг кто-то запустил без нужного числа игроков –
            // минимальный вариант: 1 мафия + 1 шериф + остальные мирные
            Collections.shuffle(list, rnd);
            if (n == 0) return;
            list.get(0).setRole(Role.MAFIA);
            if (n > 1) {
                list.get(1).setRole(Role.SHERIFF);
            }
            for (int i = 2; i < n; i++) {
                list.get(i).setRole(Role.TOWN);
            }
            return;
        }

        Collections.shuffle(list, rnd);

        // 2 мафии
        list.get(0).setRole(Role.MAFIA);
        if (n > 1) list.get(1).setRole(Role.MAFIA);

        // 1 шериф
        if (n > 2) list.get(2).setRole(Role.SHERIFF);

        // 1 доктор
        if (n > 3) list.get(3).setRole(Role.DOCTOR);

        // остальные — мирные
        for (int i = 4; i < n; i++) {
            list.get(i).setRole(Role.TOWN);
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
     *
     * Доктор и шериф считаются на стороне мирных.
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
                // TOWN, SHERIFF, DOCTOR
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
