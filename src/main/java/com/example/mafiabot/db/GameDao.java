package com.example.mafiabot.db;

import java.sql.*;
import java.time.Instant;

public class GameDao {
    private final Database db;

    public GameDao(Database db) {
        this.db = db;
    }

    /** создать новую игру для чата */
    public long createGame(long chatId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO games(chat_id, start_time, status) VALUES(?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, chatId);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, "ongoing");
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Failed to create game");
    }

    public void finishGame(long gameId, String winner) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE games SET end_time = ?, status = ?, winner = ? WHERE id = ?")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, "finished");
            ps.setString(3, winner);
            ps.setLong(4, gameId);
            ps.executeUpdate();
        }
    }
}
