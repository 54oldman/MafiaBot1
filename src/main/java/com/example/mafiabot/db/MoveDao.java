package com.example.mafiabot.db;

import java.sql.*;

public class MoveDao {
    private final Database db;

    public MoveDao(Database db) {
        this.db = db;
    }

    public long insertMove(long gameId, long playerId,
                           String moveType, String moveData) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO moves(game_id, player_id, move_type, move_data) VALUES(?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, gameId);
            ps.setLong(2, playerId);
            ps.setString(3, moveType);
            ps.setString(4, moveData);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Failed to insert move");
    }
}
