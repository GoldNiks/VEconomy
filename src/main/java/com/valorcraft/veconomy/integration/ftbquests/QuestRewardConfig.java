package com.valorcraft.veconomy.integration.ftbquests;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.valorcraft.veconomy.VEconomyMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Таблица наград за квесты FTB Quests.
 * <p>
 * Файл {@code config/veconomy-quests.json} (создаётся автоматически при первом запуске):
 * <pre>{@code
 * {
 *   // Включить ли числовой «Custom Reward» (название = число) как способ награды
 *   "customRewardEnabled": true,
 *   // Сумма за квест в главах, не перечисленных ниже (0 = ничего)
 *   "defaultPerQuest": 0,
 *   // "Название главы": сумма за один квест
 *   "chapters": {
 *     "Глава 1": 25
 *   },
 *   // Определённый квест (по id из FTB Quests): сумма. Приоритетнее главы.
 *   "quests": {
 *     "1234567890123456": 50
 *   }
 * }
 * }</pre>
 * Суммы — в минимальных единицах валюты. Строки {@code //} игнорируются для удобства
 * ручного редактирования. Файл перечитывается командой {@code /economy admin reload}
 * и при старте сервера.
 */
public final class QuestRewardConfig {

    private static final String FILE_NAME = "veconomy-quests.json";

    private static final Map<String, Long> CHAPTERS = new LinkedHashMap<>();
    private static final Map<Long, Long> QUEST_OVERRIDES = new LinkedHashMap<>();
    private static long defaultPerQuest;
    private static boolean customRewardEnabled = true;

    private QuestRewardConfig() {}

    /** Включён ли числовой «Custom Reward» (название награды = число). */
    public static boolean customRewardEnabled() {
        return customRewardEnabled;
    }

    /** Награда за квест (минимальные единицы): сначала точная сумма по id квеста, иначе по главе. */
    public static long rewardForQuest(Long questId, String chapterTitle) {
        if (questId != null) {
            Long override = QUEST_OVERRIDES.get(questId);
            if (override != null) {
                return override;
            }
        }
        return rewardForChapter(chapterTitle);
    }

    /** Награда за один квест в главе {@code chapterTitle} (минимальные единицы). */
    public static long rewardForChapter(String chapterTitle) {
        if (chapterTitle == null) {
            return defaultPerQuest;
        }
        return CHAPTERS.getOrDefault(chapterTitle, defaultPerQuest);
    }

    /** Загрузить конфиг из каталога конфигов Forge. */
    public static void load(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        CHAPTERS.clear();
        QUEST_OVERRIDES.clear();
        defaultPerQuest = 0;
        customRewardEnabled = true;
        if (!Files.exists(file)) {
            writeTemplate(file);
            VEconomyMod.LOGGER.info("Создан конфиг наград квестов: {}", file);
            return;
        }
        try {
            String content = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            if (root.has("customRewardEnabled")) {
                customRewardEnabled = root.get("customRewardEnabled").getAsBoolean();
            }
            defaultPerQuest = root.has("defaultPerQuest") ? root.get("defaultPerQuest").getAsLong() : 0L;
            if (root.has("chapters") && root.get("chapters").isJsonObject()) {
                JsonObject chapters = root.getAsJsonObject("chapters");
                for (Map.Entry<String, JsonElement> entry : chapters.entrySet()) {
                    long amount = entry.getValue().getAsLong();
                    if (amount > 0) {
                        CHAPTERS.put(entry.getKey(), amount);
                    }
                }
            }
            if (root.has("quests") && root.get("quests").isJsonObject()) {
                JsonObject quests = root.getAsJsonObject("quests");
                for (Map.Entry<String, JsonElement> entry : quests.entrySet()) {
                    try {
                        long questId = Long.parseLong(entry.getKey());
                        long amount = entry.getValue().getAsLong();
                        if (amount > 0) {
                            QUEST_OVERRIDES.put(questId, amount);
                        }
                    } catch (NumberFormatException e) {
                        VEconomyMod.LOGGER.warn("Пропущен id квеста '{}' (не число)", entry.getKey());
                    }
                }
            }
            VEconomyMod.LOGGER.info("Конфиг наград квестов загружен: {} глав, {} квестов, default={}, customReward={}",
                    CHAPTERS.size(), QUEST_OVERRIDES.size(), defaultPerQuest, customRewardEnabled);
        } catch (Exception e) {
            VEconomyMod.LOGGER.error("Ошибка загрузки {}: {}", file, e.toString());
        }
    }

    /** Все настроенные главы и суммы (для отладки и справки). */
    public static Map<String, Long> snapshot() {
        Map<String, Long> copy = new LinkedHashMap<>();
        copy.put("__default__", defaultPerQuest);
        copy.putAll(CHAPTERS);
        return copy;
    }

    private static void writeTemplate(Path file) {
        String template = """
                {
                  // Включить ли числовой "Custom Reward" (название награды = число)
                  "customRewardEnabled": true,
                  // Сумма за квест в главах, не перечисленных ниже (0 = ничего)
                  "defaultPerQuest": 0,
                  // Название главы: сумма за один квест (как в FTB Quests)
                  "chapters": {
                    "Глава 1": 25,
                    "Глава 2": 50
                  },
                  // Конкретный квест по id (виден в редакторе FTB Quests): приоритетнее главы
                  "quests": {
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

    /** Убрать построчные комментарии {@code //} (чтобы файл можно было комментировать). */
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
