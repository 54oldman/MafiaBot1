package com.example.mafiabot.db;

import java.sql.*;

public class GamePlayerDao {
    private final Database db;

    public GamePlayerDao(Database db) {
        this.db = db;
    }

    public void addPlayer(long gameId, long userId, String role, boolean isAi) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO game_players(game_id, user_id, role, is_ai) VALUES(?,?,?,?)")) {
            ps.setLong(1, gameId);
            ps.setLong(2, userId);
            ps.setString(3, role);
            ps.setInt(4, isAi ? 1 : 0);
            ps.executeUpdate();
        }
    }
}
