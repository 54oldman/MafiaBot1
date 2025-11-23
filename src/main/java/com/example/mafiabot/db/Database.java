package com.example.mafiabot.db;


import java.sql.*;
public class Database {
    private static final String URL = "jdbc:sqlite:mafia.db";

    public Database() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL)) {
            try (Statement s = c.createStatement()) {

                // 1. users
                s.execute("""
                        CREATE TABLE IF NOT EXISTS users (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            telegram_id INTEGER NOT NULL UNIQUE,
                            username TEXT,
                            name TEXT,
                            created_at TEXT DEFAULT CURRENT_TIMESTAMP
                        );
                        """);

                // 2. games
                s.execute("""
                        CREATE TABLE IF NOT EXISTS games (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            chat_id INTEGER NOT NULL,
                            start_time TEXT,
                            end_time TEXT,
                            status TEXT,
                            winner TEXT
                        );
                        """);

                // 3. game_players
                s.execute("""
                        CREATE TABLE IF NOT EXISTS game_players (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            game_id INTEGER NOT NULL,
                            user_id INTEGER NOT NULL,
                            role TEXT,
                            is_ai INTEGER NOT NULL DEFAULT 0
                        );
                        """);

                // 4. moves
                s.execute("""
                        CREATE TABLE IF NOT EXISTS moves (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            game_id INTEGER NOT NULL,
                            player_id INTEGER NOT NULL,
                            move_type TEXT,
                            move_data TEXT,
                            timestamp TEXT DEFAULT CURRENT_TIMESTAMP
                        );
                        """);

                // 5. training_data
                s.execute("""
                        CREATE TABLE IF NOT EXISTS training_data (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            game_id INTEGER NOT NULL,
                            move_id INTEGER NOT NULL,
                            player_role TEXT,
                            state_snapshot TEXT,
                            action_taken TEXT,
                            outcome TEXT
                        );
                        """);
            }
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL);
    }
}
