package com.example.mafiabot.telegram;

import com.example.mafiabot.game.GameController;
import com.example.mafiabot.game.GameManager;
import com.example.mafiabot.game.GameSession;
import com.example.mafiabot.game.Phase;
import com.example.mafiabot.game.Player;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

public class MafiaTelegramBot extends TelegramLongPollingBot {

    private final String botUsername;
    private final String botToken;
    private final GameController controller;

    public MafiaTelegramBot(String botUsername, String botToken, GameController controller) {
        this.botUsername = botUsername;
        this.botToken = botToken;
        this.controller = controller;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage()) return;

        Message msg = update.getMessage();
        long chatId = msg.getChatId();
        long fromId = msg.getFrom().getId();
        String text = msg.getText() == null ? "" : msg.getText().trim();

        try {
            if (text.equals("/start")) {
                send(chatId,
                        "Привет! Это Мафия.\n" +
                                "Команды:\n" +
                                "/join - присоединиться к игре\n" +
                                "/startgame - начать игру (первая фаза: ночь)\n" +
                                "/ai_move - ход мафии (НОЧЬ)\n" +
                                "/accuse @username - казнить игрока (ДЕНЬ)\n" +
                                "/status - состояние игроков и фаза\n" +
                                "/newgame - начать новую игру");
            } else if (text.equals("/join")) {
                String username = msg.getFrom().getUserName() != null
                        ? msg.getFrom().getUserName()
                        : msg.getFrom().getFirstName();
                String reply = controller.handleJoin(chatId, fromId, username);
                send(chatId, reply);

            } else if (text.equals("/startgame")) {
                String reply = controller.handleStartGame(chatId);
                send(chatId, reply);

                GameSession session = controller.getSession(chatId);
                if (session != null) {
                    GameManager gm = session.getManager();
                    for (Player p : gm.getPlayers()) {
                        send(p.getChatId(), "Твоя роль: " + p.getRole());
                    }
                }

            } else if (text.startsWith("/accuse")) {
                String[] parts = text.split("\\s+");
                if (parts.length < 2) {
                    send(chatId, "Укажи цель: /accuse @username");
                    return;
                }
                String targetName = parts[1].replace("@", "");
                String reply = controller.handleHumanAccuse(chatId, fromId, targetName);
                send(chatId, reply);

            } else if (text.equals("/ai_move")) {
                String reply = controller.handleAiMove(chatId);
                send(chatId, reply);

            } else if (text.equals("/status")) {
                GameSession session = controller.getSession(chatId);
                if (session == null) {
                    send(chatId, "Игра ещё не создана.");
                    return;
                }
                GameManager gm = session.getManager();
                StringBuilder sb = new StringBuilder();
                sb.append("Фаза: ").append(gm.getPhase()).append("\n");
                sb.append("Игроки:\n");
                for (Player p : gm.getPlayers()) {
                    sb.append(p.getUsername())
                            .append(" - ").append(p.getRole())
                            .append(" - ").append(p.isAlive() ? "alive" : "dead")
                            .append("\n");
                }
                send(chatId, sb.toString());

            } else if (text.equals("/newgame")) {
                String reply = controller.handleNewGame(chatId);
                send(chatId, reply);
            }

        } catch (Exception e) {
            e.printStackTrace();
            send(chatId, "Ошибка: " + e.getMessage());
        }
    }

    private void send(long chatId, String text) {
        SendMessage sm = new SendMessage(String.valueOf(chatId), text);
        try {
            execute(sm);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}
