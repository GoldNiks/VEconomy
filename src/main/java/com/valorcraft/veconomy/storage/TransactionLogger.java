package com.valorcraft.veconomy.storage;

import com.valorcraft.veconomy.config.EconomyConfig;
import com.valorcraft.veconomy.util.MoneyFormatter;
import net.minecraft.world.entity.player.Player;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Пишет все транзакции в logs/economy_transactions.log (включается конфигом logTransactions). */
public final class TransactionLogger {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static BufferedWriter writer;
    private static boolean enabled;

    public static void init(Path logsDirectory) {
        enabled = EconomyConfig.LOG_TRANSACTIONS.get();
        if (!enabled) {
            return;
        }
        try {
            Files.createDirectories(logsDirectory);
            Path file = logsDirectory.resolve("economy_transactions.log");
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            logLine("Лог транзакций инициализирован");
        } catch (IOException e) {
            writer = null;
            enabled = false;
        }
    }

    public static void logTransaction(Player player, String action, double amount, double newBalance) {
        logLine(String.format("player=%s uuid=%s action=%s amount=%s newBalance=%s",
                player.getGameProfile().getName(), player.getUUID(), action,
                MoneyFormatter.format(amount), MoneyFormatter.format(newBalance)));
    }

    public static synchronized void logLine(String line) {
        if (!enabled || writer == null) {
            return;
        }
        try {
            writer.write("[" + LocalDateTime.now().format(TIME) + "] " + line);
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
        }
    }

    public static synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            writer = null;
        }
    }

    private TransactionLogger() {}
}
