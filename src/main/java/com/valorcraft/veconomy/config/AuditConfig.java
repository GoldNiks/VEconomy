package com.valorcraft.veconomy.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.valorcraft.veconomy.VEconomyMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Пороги сигналов подозрительной активности из {@code config/veconomy-audit.json}.
 * Необязательный конфиг: при отсутствии файла или ошибке парсинга используются
 * значения по умолчанию (последняя корректная конфигурация сохраняется).
 */
public final class AuditConfig {

    public static final String FILE_NAME = "veconomy-audit.json";

    private static volatile Settings current = Settings.defaults();

    private AuditConfig() {}

    /** Текущие пороги сигналов (последняя корректная конфигурация). */
    public static Settings settings() {
        return current;
    }

    /** Загрузить/перезагрузить пороги; при отсутствии файла создаётся шаблон. */
    public static void load(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            writeTemplate(file);
            current = Settings.defaults();
            return;
        }
        try {
            String content = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            JsonElement root = JsonParser.parseString(content);
            if (root == null || !root.isJsonObject()) {
                throw new IllegalArgumentException("корень файла должен быть объектом {\"signals\": {...}}");
            }
            JsonObject signals = root.getAsJsonObject().getAsJsonObject("signals");
            Settings parsed = parse(signals);
            current = parsed;
            VEconomyMod.LOGGER.info("Конфиг аудита загружен из {}", file);
        } catch (Exception e) {
            VEconomyMod.LOGGER.error("Ошибка конфигурации аудита ({}): {}. Используется последняя "
                    + "корректная конфигурация.", file, e.toString());
        }
    }

    private static Settings parse(JsonObject signals) {
        if (signals == null) {
            throw new IllegalArgumentException("обязательный объект \"signals\" отсутствует");
        }
        boolean enabled = bool(signals, "enabled", Settings.defaults().enabled());
        int windowMinutes = intRange(signals, "windowMinutes", 1, 24 * 60,
                Settings.defaults().windowMinutes());
        int transferSpamCount = intRange(signals, "transferSpamCount", 1, 100_000,
                Settings.defaults().transferSpamCount());
        int roundTripExchanges = intRange(signals, "roundTripExchanges", 1, 100_000,
                Settings.defaults().roundTripExchanges());
        long oversizedTransferAmount = longRange(signals, "oversizedTransferAmount", 1, Long.MAX_VALUE,
                Settings.defaults().oversizedTransferAmount());
        int newAccountDays = intRange(signals, "newAccountDays", 1, 3650,
                Settings.defaults().newAccountDays());
        long newAccountTransferAmount = longRange(signals, "newAccountTransferAmount", 1, Long.MAX_VALUE,
                Settings.defaults().newAccountTransferAmount());
        long rapidForwardAmount = longRange(signals, "rapidForwardAmount", 1, Long.MAX_VALUE,
                Settings.defaults().rapidForwardAmount());
        int rapidForwardWindowMinutes = intRange(signals, "rapidForwardWindowMinutes", 1, 24 * 60,
                Settings.defaults().rapidForwardWindowMinutes());
        int transferLoopLength = intRange(signals, "transferLoopLength", 3, 100,
                Settings.defaults().transferLoopLength());
        int highPairFrequencyExchanges = intRange(signals, "highPairFrequencyExchanges", 1, 100_000,
                Settings.defaults().highPairFrequencyExchanges());
        int newAccountConcentrationSources = intRange(signals, "newAccountConcentrationSources", 2, 10_000,
                Settings.defaults().newAccountConcentrationSources());
        int repeatedDestinationTransfers = intRange(signals, "repeatedDestinationTransfers", 1, 100_000,
                Settings.defaults().repeatedDestinationTransfers());
        int retentionDays = intRange(signals, "retentionDays", 1, 3650,
                Settings.defaults().retentionDays());
        return new Settings(enabled, windowMinutes, transferSpamCount, roundTripExchanges,
                oversizedTransferAmount, newAccountDays, newAccountTransferAmount,
                rapidForwardAmount, rapidForwardWindowMinutes, transferLoopLength,
                highPairFrequencyExchanges, newAccountConcentrationSources,
                repeatedDestinationTransfers, retentionDays);
    }

    private static boolean bool(JsonObject object, String field, boolean fallback) {
        if (!object.has(field)) {
            return fallback;
        }
        JsonElement value = object.get(field);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        throw new IllegalArgumentException("поле \"" + field + "\" должно быть true/false");
    }

    private static int intRange(JsonObject object, String field, int min, int max, int fallback) {
        if (!object.has(field)) {
            return fallback;
        }
        JsonElement value = object.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("поле \"" + field + "\" должно быть числом");
        }
        int parsed = value.getAsInt();
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("поле \"" + field + "\" вне диапазона [" + min + ", " + max + "]");
        }
        return parsed;
    }

    private static long longRange(JsonObject object, String field, long min, long max, long fallback) {
        if (!object.has(field)) {
            return fallback;
        }
        JsonElement value = object.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("поле \"" + field + "\" должно быть числом");
        }
        long parsed = value.getAsLong();
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("поле \"" + field + "\" вне диапазона [" + min + ", " + max + "]");
        }
        return parsed;
    }

    /** Пороги сигналов. */
    public record Settings(
            boolean enabled,
            /** Окно анализа (минуты). */
            int windowMinutes,
            /** Минимум переводов одного игрока в окне для сигнала TRANSFER_SPAM. */
            int transferSpamCount,
            /** Минимум обменов в обе стороны между парой для сигнала ROUNDTRIP. */
            int roundTripExchanges,
            /** Сумма перевода (мин. единицы), от которой сигнал OVERSIZED. */
            long oversizedTransferAmount,
            /** Возраст аккаунта (дней), с которого перевод считается «от нового аккаунта». */
            int newAccountDays,
            /** Сумма перевода нового аккаунта (мин. единицы) для сигнала NEW_ACCOUNT. */
            long newAccountTransferAmount,
            /** Минимум суммы (мин. единицы) для сигнала RAPID_FORWARDING. */
            long rapidForwardAmount,
            /** Окно пересылки (минуты) для сигнала RAPID_FORWARDING. */
            int rapidForwardWindowMinutes,
            /** Минимальная длина цикла (участников) для сигнала TRANSFER_LOOP. */
            int transferLoopLength,
            /** Минимум обменов пары для сигнала HIGH_PAIR_FREQUENCY. */
            int highPairFrequencyExchanges,
            /** Минимум разных отправителей на новый аккаунт для CONCENTRATION. */
            int newAccountConcentrationSources,
            /** Минимум переводов одного игрока одному получателю для SHARED_DESTINATION. */
            int repeatedDestinationTransfers,

            /** Сколько дней хранить события аудита (старше — очищаются при старте/периодически). */
            int retentionDays) {

        public static Settings defaults() {
            return new Settings(true, 30, 12, 4, 500_000L, 7, 100_000L,
                    100_000L, 5, 3, 10, 5, 10, 90);
        }
    }

    private static void writeTemplate(Path file) {
        String template = """
                {
                  // Пороги сигналов подозрительной активности.
                  // windowMinutes — окно анализа; *_count — минимум срабатываний в окне;
                  // *_amount — суммы в минимальных единицах валюты.
                  "signals": {
                    "enabled": true,
                    "windowMinutes": 30,
                    "transferSpamCount": 12,
                    "roundTripExchanges": 4,
                    "oversizedTransferAmount": 500000,
                    "newAccountDays": 7,
                    "newAccountTransferAmount": 100000,
                    "rapidForwardAmount": 100000,
                    "rapidForwardWindowMinutes": 5,
                    "transferLoopLength": 3,
                    "highPairFrequencyExchanges": 10,
                    "newAccountConcentrationSources": 5,
                    "repeatedDestinationTransfers": 10,
                    "retentionDays": 90
                  }
                }
                """;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, template, StandardCharsets.UTF_8);
        } catch (IOException e) {
            VEconomyMod.LOGGER.error("Не удалось создать {}: {}", file, e.toString());
        }
    }

    /** Убрать построчные комментарии {@code //} (как в veconomy-milestones.json). */
    private static String stripComments(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (String line : raw.split("\n", -1)) {
            int idx = line.indexOf("//");
            if (idx >= 0) {
                line = line.substring(0, idx);
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
