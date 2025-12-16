package com.example.mafiabot.telegram;

import com.example.mafiabot.game.GameController;
import com.example.mafiabot.game.GameManager;
import com.example.mafiabot.game.GameSession;
import com.example.mafiabot.game.Player;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Telegram-бот для игры в Мафию.
 */
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
        if (msg.getText() == null) return;

        long chatId = msg.getChatId();
        long fromId = msg.getFrom().getId();
        String text = msg.getText().trim();

        try {
            // /start — краткая помощь
            if (text.equals("/start")) {
                send(chatId,
                        "Привет! Это Мафия.\n" +
                                "Команды:\n" +
                                "/join - присоединиться к игре\n" +
                                "/addbots N - добавить N ботов в игру\n" +
                                "/startgame - начать игру (первая фаза: НОЧЬ)\n" +
                                "/ai_move - ход мафии (НОЧЬ)\n" +
                                "/accuse @username или /vote @username - голосовать за казнь (ДЕНЬ)\n" +
                                "/endday - завершить день и подсчитать голоса\n" +
                                "/status - состояние игроков и текущая фаза\n" +
                                "/newgame - начать новую игру");

                // /join
            } else if (text.equals("/join")) {
                String username = msg.getFrom().getUserName() != null
                        ? msg.getFrom().getUserName()
                        : msg.getFrom().getFirstName();
                String reply = controller.handleJoin(chatId, fromId, username);
                send(chatId, reply);

                // /addbots N
            } else if (text.startsWith("/addbots")) {
                String[] parts = text.split("\\s+");
                int count = 1;
                if (parts.length >= 2) {
                    try {
                        count = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        send(chatId, "Использование: /addbots N (например, /addbots 3)");
                        return;
                    }
                }
                String reply = controller.handleAddBots(chatId, count);
                send(chatId, reply);

                // /startgame
            } else if (text.equals("/startgame")) {
                String reply = controller.handleStartGame(chatId);
                send(chatId, reply);

                // рассылаем роли в личку каждому реальному игроку
                GameSession session = controller.getSession(chatId);
                if (session != null) {
                    GameManager gm = session.getManager();
                    for (Player p : gm.getPlayers()) {
                        if (p.getChatId() > 0) { // только настоящие Telegram-пользователи
                            send(p.getChatId(), "Твоя роль: " + p.getRole());
                        }
                    }
                }

                // /accuse и /vote — голосование днём
            } else if (text.startsWith("/accuse") || text.startsWith("/vote")) {
                String[] parts = text.split("\\s+");
                if (parts.length < 2) {
                    send(chatId, "Укажи цель: /vote @username");
                    return;
                }
                String targetName = parts[1].replace("@", "");
                String reply = controller.handleVote(chatId, fromId, targetName);
                send(chatId, reply);

                // /endday — завершить день и подсчитать голоса
            } else if (text.equals("/endday")) {
                String reply = controller.handleEndDay(chatId);
                send(chatId, reply);

                // /ai_move — ход мафии ночью
            } else if (text.equals("/ai_move")) {
                String reply = controller.handleAiMove(chatId);
                send(chatId, reply);

                // /status — фаза + список игроков, роли скрыты до конца игры
            } else if (text.equals("/status")) {
                GameSession session = controller.getSession(chatId);
                if (session == null) {
                    send(chatId, "Игра ещё не создана.");
                    return;
                }
                GameManager gm = session.getManager();
                boolean revealRoles = gm.isFinished(); // роли открываем только после окончания игры

                StringBuilder sb = new StringBuilder();
                sb.append("Фаза: ").append(gm.getPhase()).append("\n");
                sb.append("Игроки:\n");
                for (Player p : gm.getPlayers()) {
                    String roleStr;
                    if (revealRoles || p.getChatId() == fromId) {
                        roleStr = String.valueOf(p.getRole());
                    } else {
                        roleStr = "???";
                    }
                    sb.append(p.getUsername())
                            .append(" - ").append(roleStr)
                            .append(" - ").append(p.isAlive() ? "alive" : "dead")
                            .append("\n");
                }
                send(chatId, sb.toString());

                // /newgame — новая партия
            } else if (text.equals("/newgame")) {
                String reply = controller.handleNewGame(chatId);
                send(chatId, reply);
            }

        } catch (Exception e) {
            e.printStackTrace();
            send(chatId, "Ошибка: " + e.getMessage());
        }
    }

    /** Отправка текста. */
    private void send(long chatId, String text) {
        SendMessage sm = new SendMessage(String.valueOf(chatId), text);
        try {
            execute(sm);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}
