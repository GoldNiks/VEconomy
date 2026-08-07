package com.valorcraft.veconomy.ui;

import com.valorcraft.veconomy.util.MessageService;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

import java.util.List;

/**
 * Композиция сообщений VEconomy: символ, заголовок, подпись, значение и пояснение
 * собираются из отдельных компонентов со своими цветами и интерактивностью.
 * Перевод текста выполняется {@link MessageService}; здесь только цвета и стили.
 * <p>
 * Разрешённые символы ванильного шрифта: ◆ • ✓ ✕ ⚠ + − ★ ⏱ ← → (эмодзи, длинные
 * ASCII-рамки и градиенты не используются).
 */
public final class EconomyComponents {

    private static final int LABEL_WIDTH = 16;
    private static final int AMOUNT_WIDTH = 7;

    private EconomyComponents() {}

    // ---------------------------------------------------------------- primitives

    /** Текст с заданным цветом. */
    public static MutableComponent colored(String text, TextColor color) {
        return Component.literal(text == null ? "" : text)
                .withStyle(style -> style.withColor(color));
    }

    public static MutableComponent text(String text) {
        return colored(text, EconomyTheme.TEXT);
    }

    public static MutableComponent muted(String text) {
        return colored(text, EconomyTheme.MUTED);
    }

    public static MutableComponent info(String text) {
        return colored(text, EconomyTheme.INFO);
    }

    public static MutableComponent value(String text) {
        return colored(text, EconomyTheme.VALUE);
    }

    // ---------------------------------------------------------------- symbol lines

    /** «◆ <title>» золотым. */
    public static MutableComponent header(String title) {
        return colored("◆ ", EconomyTheme.GOLD).append(colored(title, EconomyTheme.GOLD));
    }

    /** «◆ <title>    <counter>» — заголовок со счётчиком справа. */
    public static MutableComponent headerWith(String title, MutableComponent counter) {
        return header(title).append(colored("    ", EconomyTheme.MUTED)).append(counter);
    }

    /** «✓ <text>» — успех. */
    public static MutableComponent success(String text) {
        return colored("✓ ", EconomyTheme.SUCCESS).append(text(text));
    }

    /** «✕ <text>» — ошибка. */
    public static MutableComponent error(String text) {
        return colored("✕ ", EconomyTheme.ERROR).append(text(text));
    }

    /** «⚠ <text>» — предупреждение. */
    public static MutableComponent warning(String text) {
        return colored("⚠ ", EconomyTheme.WARNING).append(text(text));
    }

    /** «• <label>  <value>» — строка информации. */
    public static MutableComponent entry(String label, MutableComponent value) {
        return colored("• " + padRight(label, LABEL_WIDTH), EconomyTheme.TEXT).append(value);
    }

    /** «⏱ <label>  <value>» — строка времени. */
    public static MutableComponent timeEntry(String label, MutableComponent value) {
        return colored("⏱ " + padRight(label, LABEL_WIDTH), EconomyTheme.INFO).append(value);
    }

    /** Сумма жирным значением (VALUE). */
    public static MutableComponent money(String formatted) {
        return Component.literal(formatted)
                .withStyle(style -> style.withColor(EconomyTheme.VALUE).withBold(true));
    }

    /** «<sign> <amount>» цветом (доход/расход). */
    public static MutableComponent amount(String sign, String amountText, TextColor color) {
        return colored(sign, color).append(colored(" ", color)).append(colored(amountText, color));
    }

    /** «+ <amount>» — доход (SUCCESS). */
    public static MutableComponent income(String amountText) {
        return amount("+", amountText, EconomyTheme.SUCCESS);
    }

    /** «− <amount>» — расход (EXPENSE). */
    public static MutableComponent expense(String amountText) {
        return amount("−", amountText, EconomyTheme.EXPENSE);
    }

    /** «★ <title>» — заголовок награды. */
    public static MutableComponent rewardHeader(String title) {
        return colored("★ ", EconomyTheme.SPECIAL).append(colored(title, EconomyTheme.TEXT));
    }

    /** Экран награды: «★ title» + отдельная строка с доходом. */
    public static MutableComponent reward(String title, String amountText) {
        MutableComponent out = rewardHeader(title);
        if (amountText != null && !amountText.isBlank()) {
            out.append(Component.literal("\n"));
            out.append(income(amountText));
        }
        return out;
    }

    /** « → <name>» — счётчик-имя получателя. */
    public static MutableComponent toPlayer(String name) {
        return colored(" → ", EconomyTheme.MUTED).append(value(name));
    }

    /** «← <name>» — имя отправителя для получателя. */
    public static MutableComponent fromPlayer(String name) {
        return colored(" ← ", EconomyTheme.MUTED).append(value(name));
    }

    /** Стрелка «→» исходящей операции (без имени). */
    public static MutableComponent toPlayerOut() {
        return colored(" →", EconomyTheme.MUTED);
    }

    /** Стрелка «←» входящей операции (без имени). */
    public static MutableComponent toPlayerIn() {
        return colored(" ←", EconomyTheme.MUTED);
    }

    // ---------------------------------------------------------------- interactive

    /** Применить клик и подсказку к скомпонованному компоненту. */
    public static MutableComponent clickable(MutableComponent base, ClickEvent.Action action,
                                             String command, Component hover) {
        return base.withStyle(style -> style
                .withClickEvent(new ClickEvent(action, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
    }

    /** Команда подставляется в чат (SUGGEST_COMMAND). */
    public static MutableComponent clickableSuggest(String text, String command, Component hover) {
        return clickable(muted(text), ClickEvent.Action.SUGGEST_COMMAND, command, hover);
    }

    /** Команда выполняется (RUN_COMMAND). */
    public static MutableComponent clickableRun(String text, String command, Component hover) {
        return clickable(muted(text), ClickEvent.Action.RUN_COMMAND, command, hover);
    }

    // ---------------------------------------------------------------- layout helpers

    /** Дополнить подпись пробелами до ширины {@link #LABEL_WIDTH}. */
    public static String padRight(String label, int width) {
        StringBuilder sb = new StringBuilder(label == null ? "" : label);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /** Выровнять сумму вправо до ширины {@link #AMOUNT_WIDTH}. */
    public static String padAmount(String amount, int width) {
        String value = amount == null ? "" : amount;
        if (value.length() >= width) {
            return value;
        }
        return " ".repeat(width - value.length()) + value;
    }

    /**
     * Навигация по страницам истории: неактивная стрелка не выводится.
     * {@code command} — префикс команды (например {@code /money history сомc}),
     * к которому дописывается номер следующей/предыдущей страницы.
     */
    public static MutableComponent navigation(String locale, String command,
                                              String backKey, String nextKey,
                                              int currentPage, int totalPages) {
        int current = Math.max(1, currentPage);
        int total = Math.max(1, totalPages);
        MutableComponent out = Component.empty();
        String pad = "      ";
        if (current > 1) {
            out.append(colored("← ", EconomyTheme.MUTED))
                    .append(clickableRun(
                            MessageService.text(locale, backKey),
                            command + (current - 1),
                            Component.literal(MessageService.text(locale, backKey + ".hover"))));
            out.append(colored(pad, EconomyTheme.MUTED));
        } else {
            out.append(colored(pad + "  ", EconomyTheme.MUTED));
        }
        if (current < total) {
            out.append(clickableRun(
                            MessageService.text(locale, nextKey),
                            command + (current + 1),
                            Component.literal(MessageService.text(locale, nextKey + ".hover"))))
                    .append(colored(" →", EconomyTheme.MUTED));
        }
        return out;
    }

    /** Пустой компонент. */
    public static MutableComponent empty() {
        return Component.empty();
    }

    /** Многострочная подсказка из строк-компонентов (разделитель — перевод строки). */
    public static MutableComponent hover(List<Component> lines) {
        MutableComponent hover = empty();
        for (Component line : lines) {
            if (!hover.getSiblings().isEmpty()) {
                hover.append(Component.literal("\n"));
            }
            hover.append(line);
        }
        return hover;
    }
}