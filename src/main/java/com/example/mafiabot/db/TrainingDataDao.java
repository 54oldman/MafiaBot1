package com.example.mafiabot.db;

import java.sql.Connection;
import java.sql.PreparedStatement;

public class TrainingDataDao {
    private final Database db;

    public TrainingDataDao(Database db) {
        this.db = db;
    }

    public void insertTrainingRow(long gameId,
                                  long moveId,
                                  String playerRole,
                                  String stateSnapshot,
                                  String actionTaken,
                                  String outcome) throws Exception {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO training_data(game_id, move_id, player_role, state_snapshot, action_taken, outcome) " +
                             "VALUES(?,?,?,?,?,?)")) {
            ps.setLong(1, gameId);
            ps.setLong(2, moveId);
            ps.setString(3, playerRole);
            ps.setString(4, stateSnapshot);
            ps.setString(5, actionTaken);
            ps.setString(6, outcome);
            ps.executeUpdate();
        }
    }

    /** Обновить outcome для всех ходов одной игры (когда игра закончилась) */
    public void updateOutcomeForGame(long gameId, String outcome) throws Exception {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE training_data SET outcome = ? WHERE game_id = ?")) {
            ps.setString(1, outcome);
            ps.setLong(2, gameId);
            ps.executeUpdate();
        }
    }
}
