package com.example.mafiabot.game;



import java.util.*;

public class GameManager {
    private final Map<Long, Player> players = new LinkedHashMap<>();
    private final List<Round> rounds = new ArrayList<>();
    private boolean started = false;
    private final Random rnd = new Random();

    public synchronized void joinPlayer(long chatId, String username) {
        players.putIfAbsent(chatId, new Player(chatId, username));
    }

    public synchronized void startGame() {
        if (started) return;
        started = true;
        assignRoles();
    }

    private void assignRoles() {
        List<Player> list = new ArrayList<>(players.values());
        Collections.shuffle(list, rnd);
        int n = list.size();
        int mafiaCount = Math.max(1, n / 4);
        for (int i=0;i<n;i++) {
            Player p = list.get(i);
            if (i < mafiaCount) p.setRole(Role.MAFIA);
            else if (i == mafiaCount) p.setRole(Role.SHERIFF);
            else p.setRole(Role.TOWN);
        }
    }

    public synchronized String accuse(long accuserId, long targetId) {
        Player acc = players.get(accuserId);
        Player tgt = players.get(targetId);
        if (acc==null || tgt==null) return "Игрок не найден.";
        Map<String,String> move = Map.of(acc.getUsername(), "accuse "+tgt.getUsername());
        rounds.add(new Round(rounds.size()+1, move));
        return acc.getUsername() + " подозревает " + tgt.getUsername();
    }

    public Collection<Player> getPlayers() { return players.values(); }
    public List<Round> getRounds() { return rounds; }
    public boolean isStarted() { return started; }
}