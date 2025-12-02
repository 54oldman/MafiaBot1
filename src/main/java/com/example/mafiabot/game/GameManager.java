package com.example.mafiabot.game;

import java.util.*;

/**
 * Отвечает за состояние одной игры: список игроков, роли, жив/мертв, фаза и победитель.
 */
public class GameManager {

    private final Map<Long, Player> players = new LinkedHashMap<>();
    private final List<Round> rounds = new ArrayList<>();

    private boolean started = false;
    private boolean finished = false;
    private String winner = null;

    private Phase phase = Phase.LOBBY;

    private final Random rnd = new Random();

    /** Добавить игрока (пока игра не началась) */
    public synchronized void joinPlayer(long chatId, String username) {
        if (started) {
            // уже играем – новых не добавляем
            return;
        }
        players.putIfAbsent(chatId, new Player(chatId, username));
    }

    /** Старт игры + распределение ролей, первая фаза — НОЧЬ */
    public synchronized void startGame() {
        if (started) return;
        started = true;
        assignRoles();
        phase = Phase.NIGHT;
    }

    /** Распределяем роли между уже добавленными игроками */
    private void assignRoles() {
        List<Player> list = new ArrayList<>(players.values());
        if (list.isEmpty()) return;

        Collections.shuffle(list, rnd);
        int n = list.size();

        if (n == 1) {
            list.get(0).setRole(Role.TOWN);
            return;
        }

        int mafiaCount = Math.max(1, n / 4);
        int index = 0;

        // мафия
        for (int i = 0; i < mafiaCount && index < n; i++, index++) {
            list.get(index).setRole(Role.MAFIA);
        }

        // шериф
        if (index < n) {
            list.get(index).setRole(Role.SHERIFF);
            index++;
        }

        // остальные — мирные
        while (index < n) {
            list.get(index).setRole(Role.TOWN);
            index++;
        }
    }

    /** Игрок обвиняет другого – сохраняем ход раунда */
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

    /** Пометить игрока как "убитого" */
    public synchronized void kill(long targetId) {
        Player tgt = players.get(targetId);
        if (tgt != null) {
            tgt.setAlive(false);
        }
    }

    /**
     * Проверяем, не закончилась ли игра.
     * Возвращает null, если игра продолжается,
     * либо строку "MAFIA" / "TOWN", если есть победитель.
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

    // --- геттеры/сеттеры ---

    public Collection<Player> getPlayers() {
        return players.values();
    }

    public List<Round> getRounds() {
        return rounds;
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isFinished() {
        return finished;
    }

    public String getWinner() {
        return winner;
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }
}
