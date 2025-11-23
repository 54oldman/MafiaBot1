package com.example.mafiabot.db;

import java.sql.*;

public class UserDao {
    private final Database db;

    public UserDao(Database db) {
        this.db = db;
    }

    /** Получить ID пользователя в БД, при отсутствии — создать */
    public long getOrCreateUser(long telegramId, String username) throws SQLException {
        try (Connection c = db.getConnection()) {
            // пробуем найти
            try (PreparedStatement ps =
                         c.prepareStatement("SELECT id FROM users WHERE telegram_id = ?")) {
                ps.setLong(1, telegramId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong("id");
                    }
                }
            }
            // создаём
            try (PreparedStatement ps =
                         c.prepareStatement(
                                 "INSERT INTO users(telegram_id, username) VALUES(?, ?)",
                                 Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, telegramId);
                ps.setString(2, username);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to insert user");
    }
}
