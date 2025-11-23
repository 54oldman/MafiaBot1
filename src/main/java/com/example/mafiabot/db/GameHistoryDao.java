package com.example.mafiabot.db;


import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;

public class GameHistoryDao {
    private final Database db;
    public GameHistoryDao(Database db) { this.db = db; }

    public void insertMessage(long gameId, String role, String username, String message) throws Exception {
        try (Connection c = db.getConnection()) {
            PreparedStatement ps = c.prepareStatement("INSERT INTO messages(game_id, role, username, message, created_at) VALUES(?,?,?,?,?)");
            ps.setLong(1, gameId);
            ps.setString(2, role);
            ps.setString(3, username);
            ps.setString(4, message);
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
        }
    }
}