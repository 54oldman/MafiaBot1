package com.example.mafiabot;


import com.example.mafiabot.db.*;
import com.example.mafiabot.game.AIPlayer;
import com.example.mafiabot.game.GameController;
import com.example.mafiabot.telegram.MafiaTelegramBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) throws Exception {
        String botUsername = "WhoWannaBeMafiabot";
        String botToken    = "8436029855:AAFaETydOPKVZ_RTbKfyYixn4XHLYAX7cGg";

        Database db = new Database();
        UserDao userDao = new UserDao(db);
        GameDao gameDao = new GameDao(db);
        GamePlayerDao gamePlayerDao = new GamePlayerDao(db);
        MoveDao moveDao = new MoveDao(db);
        TrainingDataDao trainingDataDao = new TrainingDataDao(db);

        AIPlayer aiPlayer = new AIPlayer(moveDao, trainingDataDao);

        GameController controller =
                new GameController(userDao, gameDao, gamePlayerDao, moveDao, aiPlayer);

        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        MafiaTelegramBot bot = new MafiaTelegramBot(botUsername, botToken, controller);
        botsApi.registerBot(bot);

        System.out.println("Bot started");
    }
}
