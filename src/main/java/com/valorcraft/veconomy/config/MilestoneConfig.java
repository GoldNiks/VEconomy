package com.valorcraft.veconomy.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.activity.MilestoneDefinition;
import com.valorcraft.veconomy.activity.MilestoneType;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Определения milestones из {@code config/veconomy-milestones.json}
 * (типы ADVANCEMENT, DIMENSION_VISIT, EXTERNAL). PLAYTIME остаётся в
 * {@code economy-core.toml} (пары «секунды → награда») — отдельный файл
 * эти данные не дублирует.
 * <p>
 * Формат:
 * <pre>{@code
 * {
 *   "milestones": [
 *     {
 *       "id": "enter_nether",
 *       "type": "ADVANCEMENT",
 *       "amount": 500,
 *       "enabled": true,
 *       "message": "Добро пожаловать в Незер!",
 *       "requirements": { "advancement": "minecraft:story/enter_the_nether" }
 *     }
 *   ]
 * }
 * }</pre>
 * <p>
 * Валидация строгая: дубликаты id, неизвестный тип, неположительная сумма, сумма
 * выше лимита {@code maximumBalance}, отсутствующие/некорректные требования —
 * каждая ошибка содержит путь к файлу, id milestone и поле. Повреждённая конфигурация
 * не применяется: сервис продолжает работать с последней корректной.
 */
public final class MilestoneConfig {

    public static final String FILE_NAME = "veconomy-milestones.json";

    private static volatile List<MilestoneDefinition> definitions = List.of();
    private static volatile long maximumBalance = Long.MAX_VALUE;

    private MilestoneConfig() {}

    /** Загруженные определения (последняя корректная конфигурация). */
    public static List<MilestoneDefinition> definitions() {
        return definitions;
    }

    /** Загрузить/перезагрузить конфиг. При ошибке сохраняется последняя корректная. */
    public static void load(Path configDir, long maxBalance) {
        maximumBalance = maxBalance;
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            writeTemplate(file);
            definitions = List.of();
            VEconomyMod.LOGGER.info("Создан конфиг milestones: {}", file);
            return;
        }
        try {
            String content = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            List<MilestoneDefinition> parsed = parse(file, JsonParser.parseString(content), maxBalance);
            definitions = List.copyOf(parsed);
            VEconomyMod.LOGGER.info("Конфиг milestones загружен: {} определений из {}", parsed.size(), file);
        } catch (MilestoneConfigException e) {
            // Не применяем повреждённый конфиг: остаётся последняя корректная версия.
            VEconomyMod.LOGGER.error("Ошибка конфигурации milestones ({}): {}. Используется "
                    + "последняя корректная конфигурация ({} определений).",
                    e.fileName(), e.getMessage(), definitions.size());
        } catch (Exception e) {
            VEconomyMod.LOGGER.error("Не удалось прочитать {}: {}. Используется последняя "
                    + "корректная конфигурация.", file, e.toString());
        }
    }

    static List<MilestoneDefinition> parse(Path file, JsonElement root, long maxBalance) {
        if (root == null || !root.isJsonObject()) {
            throw new MilestoneConfigException(file, null, null,
                    "корень файла должен быть объектом {\"milestones\": [...]}");
        }
        JsonObject object = root.getAsJsonObject();
        if (!object.has("milestones") || !object.get("milestones").isJsonArray()) {
            throw new MilestoneConfigException(file, null, "milestones",
                    "обязательное поле-массив \"milestones\" отсутствует");
        }
        JsonArray array = object.getAsJsonArray("milestones");
        List<MilestoneDefinition> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : array) {
            MilestoneDefinition definition = parseOne(file, element, maxBalance);
            if (!ids.add(definition.id())) {
                throw new MilestoneConfigException(file, definition.id(), "id",
                        "дубликат id " + definition.id());
            }
            result.add(definition);
        }
        return result;
    }

    private static MilestoneDefinition parseOne(Path file, JsonElement element, long maxBalance) {
        if (element == null || !element.isJsonObject()) {
            throw new MilestoneConfigException(file, null, null,
                    "каждый milestone должен быть объектом");
        }
        JsonObject object = element.getAsJsonObject();
        String id = stringField(file, object, "id");
        String typeName = stringField(file, object, "type");
        MilestoneType type;
        try {
            type = MilestoneType.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            throw new MilestoneConfigException(file, id, "type",
                    "неизвестный тип \"" + typeName + "\" (ожидается "
                            + "PLAYTIME, ADVANCEMENT, DIMENSION_VISIT или EXTERNAL)");
        }
        long amount = longField(file, object, "amount", id);
        if (amount <= 0) {
            throw new MilestoneConfigException(file, id, "amount",
                    "сумма должна быть положительной (найдено " + amount + ")");
        }
        if (amount > maxBalance) {
            throw new MilestoneConfigException(file, id, "amount",
                    "сумма " + amount + " превышает лимит maximumBalance (" + maxBalance + ")");
        }
        boolean enabled = !object.has("enabled") || object.get("enabled").getAsBoolean();
        String message = object.has("message") && object.get("message").isJsonPrimitive()
                ? object.get("message").getAsString() : null;

        Map<String, String> requirements = new LinkedHashMap<>();
        if (object.has("requirements")) {
            if (!object.get("requirements").isJsonObject()) {
                throw new MilestoneConfigException(file, id, "requirements",
                        "должен быть объектом");
            }
            JsonObject reqs = object.getAsJsonObject("requirements");
            Set<String> unknown = new HashSet<>(reqs.keySet());
            unknown.removeAll(knownKeys(type));
            if (!unknown.isEmpty()) {
                throw new MilestoneConfigException(file, id, "requirements",
                        "неизвестные параметры " + unknown + " для типа " + type);
            }
            for (Map.Entry<String, JsonElement> entry : reqs.entrySet()) {
                requirements.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        validateRequirements(file, id, type, requirements);
        return new MilestoneDefinition(id, type, amount, enabled, requirements, message);
    }

    private static void validateRequirements(Path file, String id, MilestoneType type,
                                             Map<String, String> requirements) {
        switch (type) {
            case PLAYTIME -> {
                long seconds = parseLong(file, id, "requirements.activeSeconds",
                        requirements.get("activeSeconds"));
                if (seconds <= 0) {
                    throw new MilestoneConfigException(file, id, "activeSeconds",
                            "требуется положительное число секунд");
                }
            }
            case ADVANCEMENT -> validateResourceLocation(file, id, "advancement",
                    requirements.get("advancement"));
            case DIMENSION_VISIT -> validateResourceLocation(file, id, "dimension",
                    requirements.get("dimension"));
            case EXTERNAL -> {
                // требований нет; необязательный channel — только для читаемости
            }
        }
    }

    private static void validateResourceLocation(Path file, String id, String field, String value) {
        if (value == null || value.isBlank()) {
            throw new MilestoneConfigException(file, id, field,
                    "обязательное поле отсутствует");
        }
        if (ResourceLocation.tryParse(value) == null) {
            throw new MilestoneConfigException(file, id, field,
                    "некорректный ResourceLocation \"" + value + "\" (ожидается \"namespace:path\")");
        }
    }

    private static Set<String> knownKeys(MilestoneType type) {
        return switch (type) {
            case PLAYTIME -> Set.of("activeSeconds");
            case ADVANCEMENT -> Set.of("advancement");
            case DIMENSION_VISIT -> Set.of("dimension");
            case EXTERNAL -> Set.of("channel");
        };
    }

    private static String stringField(Path file, JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || object.get(field).getAsString().isBlank()) {
            throw new MilestoneConfigException(file, null, field,
                    "обязательное строковое поле \"" + field + "\" отсутствует");
        }
        return object.get(field).getAsString().trim();
    }

    private static long longField(Path file, JsonObject object, String field, String id) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isNumber()) {
            throw new MilestoneConfigException(file, id, field,
                    "обязательное числовое поле \"" + field + "\" отсутствует");
        }
        return object.get(field).getAsLong();
    }

    private static long parseLong(Path file, String id, String field, String value) {
        if (value == null) {
            throw new MilestoneConfigException(file, id, field,
                    "обязательное поле отсутствует");
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new MilestoneConfigException(file, id, field,
                    "некорректное число \"" + value + "\"");
        }
    }

    private static void writeTemplate(Path file) {
        String template = """
                {
                  // Milestones с настраиваемым условием (типы ADVANCEMENT, DIMENSION_VISIT, EXTERNAL).
                  // PLAYTIME-пороги задаются в economy-core.toml ([milestones] rewards).
                  // Поля: id (уникальный), type, amount (минимальные единицы, > 0, <= maximumBalance),
                  // enabled, requirements (обязательны по типу), message (необязательное уведомление).
                  "milestones": [
                    {
                      "id": "enter_nether",
                      "type": "ADVANCEMENT",
                      "amount": 500,
                      "enabled": true,
                      "message": "Добро пожаловать в Незер!",
                      "requirements": {
                        "advancement": "minecraft:story/enter_the_nether"
                      }
                    },
                    {
                      "id": "visit_moon",
                      "type": "DIMENSION_VISIT",
                      "amount": 750,
                      "enabled": true,
                      "requirements": {
                        "dimension": "ad_astra:moon"
                      }
                    },
                    {
                      "id": "event_bonus",
                      "type": "EXTERNAL",
                      "amount": 1000,
                      "enabled": true,
                      "requirements": {
                        "channel": "events"
                      }
                    }
                  ]
                }
                """;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, template, StandardCharsets.UTF_8);
        } catch (IOException e) {
            VEconomyMod.LOGGER.error("Не удалось создать {}: {}", file, e.toString());
        }
    }

    /** Убрать построчные комментарии {@code //} (как в veconomy-quests.json). */
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

    /** Ошибка валидации конфигурации: файл + id milestone + поле + описание. */
    public static final class MilestoneConfigException extends RuntimeException {
        private final String fileName;

        MilestoneConfigException(Path file, String milestoneId, String field, String problem) {
            super((milestoneId == null ? "(без id)" : "milestone \"" + milestoneId + "\"")
                    + (field == null ? "" : ", поле \"" + field + "\"") + ": " + problem);
            this.fileName = file.getFileName().toString();
        }

        public String fileName() {
            return fileName;
        }
    }
}
