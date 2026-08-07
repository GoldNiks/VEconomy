package com.valorcraft.veconomy.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.valorcraft.veconomy.VEconomyMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Серверная локализация сообщений VEconomy.
 * <p>
 * Языковые файлы грузятся из ресурсов мода ({@code assets/economy_core/lang/...}),
 * а переданный клиенту {@link Component} всегда строится из уже переведённого
 * текста — клиенту не нужен мод или языковые файлы, непереведённые ключи в чате
 * не появляются.
 * <p>
 * Выбор языка: у игрока берётся {@code ServerPlayer#getLanguage()} (client
 * information), для консоли/не-игрока используется {@link #DEFAULT_LOCALE}.
 * Неизвестный язык и отсутствие ключа в запрошенном языке проваливаются на
 * {@link #DEFAULT_LOCALE}; если ключа нет и там — возвращается пустой текст
 * (сырой ключ игроку не показывается), в лог пишется WARN/ERROR.
 * <p>
 * Форматирование: {@link String#format(Locale, String, Object...)} с
 * CSS-плейсхолдерами {@code %s}, {@code %d}, позиционными ({@code %1$s}),
 * экранированием {@code %%} и переносом английской локали. Ошибка формата не
 * ломает выполнение: возвращается исходный шаблон, в лог пишется warning.
 */
public final class MessageService {

    static final String DEFAULT_LOCALE = "ru_ru";
    static final String EN_LOCALE = "en_us";

    private static final Map<String, Map<String, String>> LANGUAGES = new ConcurrentHashMap<>();
    private static final Set<String> WARNED_MISSING = ConcurrentHashMap.newKeySet();

    private MessageService() {}

    // ---------------------------------------------------------------- loading

    private static Map<String, String> load(String locale) {
        String path = "/assets/economy_core/lang/" + locale + ".json";
        try (InputStream in = MessageService.class.getResourceAsStream(path)) {
            if (in == null) {
                VEconomyMod.LOGGER.warn("Локализация {} не найдена в ресурсах мода ({})", locale, path);
                return Map.of();
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root == null || !root.isJsonObject()) {
                    VEconomyMod.LOGGER.warn("Локализация {} повреждена (ожидался JSON-объект)", locale);
                    return Map.of();
                }
                JsonObject object = root.getAsJsonObject();
                Map<String, String> table = new java.util.HashMap<>();
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    table.put(entry.getKey(), entry.getValue() == null || !entry.getValue().isJsonPrimitive()
                            ? "" : entry.getValue().getAsString());
                }
                return table;
            }
        } catch (IOException e) {
            VEconomyMod.LOGGER.warn("Не удалось прочитать локализацию {}: {}", locale, e.toString());
            return Map.of();
        }
    }

    private static Map<String, String> table(String locale) {
        return LANGUAGES.computeIfAbsent(locale, MessageService::load);
    }

    /** {@code true}, если загруженная таблица локали содержит ключ. */
    static boolean hasKey(String locale, String key) {
        return LANGUAGES.computeIfAbsent(locale, MessageService::load).containsKey(key);
    }

    /** Все ключи языка (для проверки полноты набережных в тестах). */
    static Set<String> keys(String locale) {
        return LANGUAGES.computeIfAbsent(locale, MessageService::load).keySet();
    }

    // ---------------------------------------------------------------- locale

    /** Нормализованный язык команды: игрок — по его настройкам, иначе дефолт. */
    public static String locale(CommandSourceStack source) {
        if (source == null) {
            return DEFAULT_LOCALE;
        }
        ServerPlayer player = source.getPlayer();
        return player != null ? locale(player) : DEFAULT_LOCALE;
    }

    /** Нормализованный язык игрока по его клиентской информации. */
    public static String locale(ServerPlayer player) {
        if (player == null) {
            return DEFAULT_LOCALE;
        }
        try {
            return normalizeLocale(player.getLanguage());
        } catch (Throwable t) {
            VEconomyMod.LOGGER.warn("Не удалось получить язык игрока {}: {}", player.getName(), t.toString());
            return DEFAULT_LOCALE;
        }
    }

    /**
     * Привести язык клиента к yandex-имени файла локали ({@code ru_RU}->{@code ru_ru},
     * {@code ru-RU}->{@code ru_ru}, {@code en_GB}->{@code en_us}). Неизвестный язык —
     * дефолтный.
     */
    public static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String normalized = locale.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        String base = normalized.split("_", 2)[0].trim();
        if (base.equals("ru")) {
            return DEFAULT_LOCALE;
        }
        if (base.equals("en")) {
            return EN_LOCALE;
        }
        return DEFAULT_LOCALE;
    }

    // ---------------------------------------------------------------- rendering

    /** Локализованное сообщение (MutableComponent со стилизуемым текстом). */
    public static MutableComponent message(CommandSourceStack source, String key, Object... args) {
        return message(locale(source), key, args);
    }

    /** Локализованное сообщение по языку конкретного игрока. */
    public static MutableComponent message(ServerPlayer player, String key, Object... args) {
        return message(locale(player), key, args);
    }

    /** Локализованное сообщение для явно указанной локали. */
    public static MutableComponent message(String locale, String key, Object... args) {
        return Component.literal(plainText(locale, key, args));
    }

    /** Текстовое (не Component) сообщение по source-команды. */
    public static String text(CommandSourceStack source, String key, Object... args) {
        return plainText(locale(source), key, args);
    }

    /** Текстовое сообщение для явной локали. Прочее: {@link #plainText}. */
    public static String text(String locale, String key, Object... args) {
        return plainText(locale, key, args);
    }

    // ---------------------------------------------------------------- rendering

    /** Основной путь рендеринга: перевод + подстановка аргументов. */
    static String plainText(String locale, String key, Object... args) {
        if (key == null) {
            return "";
        }
        String normalized = normalizeLocale(locale);
        String raw = lookup(normalized, key);
        if (raw == null) {
            return "";
        }
        Object[] flat = args == null ? new Object[0] : Arrays.stream(args)
                .map(arg -> toFlatArg(arg, normalized)).toArray();
        String formatted;
        try {
            formatted = String.format(Locale.ROOT, raw, flat);
        } catch (Exception e) {
            VEconomyMod.LOGGER.warn("Ошибка формата сообщения {} (locale={}): {}", key, normalized, e.toString());
            return raw;
        }
        return formatted;
    }

    /** Перевод без fallback (используется внутри {@link #plainText}). */
    private static String lookup(String locale, String key) {
        Map<String, String> primary = table(locale);
        if (primary.containsKey(key)) {
            return primary.get(key);
        }
        if (!locale.equals(DEFAULT_LOCALE)) {
            Map<String, String> fallback = table(DEFAULT_LOCALE);
            if (fallback.containsKey(key)) {
                return fallback.get(key);
            }
        }
        if (WARNED_MISSING.add(key)) {
            VEconomyMod.LOGGER.warn("Ключ локализации не найден в {} и {}: {}", locale, DEFAULT_LOCALE, key);
        }
        return null;
    }

    /**
     * Привести аргумент к виду для подстановки: числа/булевы как есть (для {@code %d}),
     * вложенные компоненты — переводятся в текст через {@link #flatten}, остальное —
     * {@code String.valueOf}.
     */
    private static Object toFlatArg(Object arg, String locale) {
        if (arg instanceof Component component) {
            return flatten(component, locale, new StringBuilder()).toString();
        }
        return arg;
    }

    /** Рекурсивный разбор вложенного Component в текст локали. */
    private static StringBuilder flatten(Component component, String locale, StringBuilder builder) {
        if (component == null) {
            return builder;
        }
        ComponentContents contents = component.getContents();
        if (contents instanceof TranslatableContents translatable) {
            Object[] subArgs = translatable.getArgs();
            Object[] flat = subArgs == null ? new Object[0]
                    : Arrays.stream(subArgs).map(a -> a instanceof Component nested
                    ? flatten(nested, locale, new StringBuilder()).toString() : a).toArray();
            String sub = plainText(locale, translatable.getKey(), flat);
            builder.append(sub);
        } else if (contents instanceof LiteralContents literal) {
            builder.append(literal.text());
        }
        if (component.getSiblings() != null) {
            for (Component sibling : component.getSiblings()) {
                flatten(sibling, locale, builder);
            }
        }
        return builder;
    }
}