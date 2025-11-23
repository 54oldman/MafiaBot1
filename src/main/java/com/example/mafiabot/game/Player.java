package com.example.mafiabot.game;

public class Player {
    private final long chatId; // телеграм userId
    private final String username;
    private Role role;
    private boolean alive = true;

    public Player(long chatId, String username) {
        this.chatId = chatId;
        this.username = username;
    }


    public long getChatId() { return chatId; }
    public String getUsername() { return username; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public boolean isAlive() { return alive; }
    public void setAlive(boolean alive) { this.alive = alive; }
}