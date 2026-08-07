package com.valorcraft.veconomy.command;

import com.valorcraft.veconomy.activity.ActivityService.ActivityInfo;
import com.valorcraft.veconomy.activity.WeeklyFundService.NotEligibleReason;
import com.valorcraft.veconomy.activity.WeeklyFundService.WeeklyPlayerInfo;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.persistence.TransactionRow;
import com.valorcraft.veconomy.ui.EconomyComponents;
import com.valorcraft.veconomy.ui.EconomyTheme;
import com.valorcraft.veconomy.util.MessageService;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты сборки экранов пользовательских команд. Все суммы передаются уже
 * отформатированными строками, поэтому ядро (форматтер/БД) не запускается.
 */
class MoneyCommandScreenTest {

    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    // ---------------------------------------------------------------- helpers

    private static String compiled(Component component) {
        StringBuilder builder = new StringBuilder();
        appendCompiled(component, builder);
        return builder.toString();
    }

    private static void appendCompiled(Component component, StringBuilder builder) {
        if (component.getContents() instanceof LiteralContents literal) {
            builder.append(literal.text());
        } else if (component.getContents() instanceof TranslatableContents) {
            builder.append("<key:" + component.toString() + ">");
        }
        if (component.getSiblings() != null) {
            component.getSiblings().forEach(s -> appendCompiled(s, builder));
        }
    }

    private static void assertLiteralOnly(Component component) {
        if (component.getContents() instanceof TranslatableContents) {
            throw new AssertionError("untranslated translatable contents in screen");
        }
        if (component.getSiblings() != null) {
            component.getSiblings().forEach(MoneyCommandScreenTest::assertLiteralOnly);
        }
    }

    private static TransactionRow row(long amount, UUID source, UUID target) {
        return new TransactionRow("tx-1", TransactionType.PLAYER_TRANSFER, source, target,
                amount, 1_700_000_000_000L, source, "pay:Test", "idem", Map.of(),
                1000L, 900L);
    }

    // ---------------------------------------------------------------- balance

    @Test
    void balanceScreenShowsTitleAmountAndHint() {
        MutableComponent screen = MoneyCommand.balanceScreen("ru_ru", "12 450 монет", "монет");
        String text = compiled(screen);
        assertTrue(text.contains("Ваш баланс"));
        assertTrue(text.contains("12 450 монет"));
        assertTrue(text.contains("/money history"));
        assertLiteralOnly(screen);
    }

    @Test
    void balanceOtherScreenShowsPlayerAndAmount() {
        MutableComponent screen = MoneyCommand.balanceOtherScreen("ru_ru", "Steve", "⛃250");
        String text = compiled(screen);
        assertTrue(text.contains("Баланс игрока"));
        assertTrue(text.contains("Steve"));
        assertTrue(text.contains("⛃250"));
        assertLiteralOnly(screen);
    }

    // ---------------------------------------------------------------- pay

    @Test
    void paySenderScreenShowsExpenseAndNewBalance() {
        MutableComponent screen = MoneyCommand.paySenderScreen("ru_ru", "250", "Raud", "12 200");
        String text = compiled(screen);
        assertTrue(text.contains("Вы перевели"));
        assertTrue(text.contains("− 250"));
        assertTrue(text.contains("Raud"));
        assertTrue(text.contains("12 200"));
        assertLiteralOnly(screen);
    }

    @Test
    void payReceiverScreenShowsIncomeFromPlayer() {
        MutableComponent screen = MoneyCommand.payReceiverScreen("ru_ru", "250", "Raud", "4 550");
        String text = compiled(screen);
        assertTrue(text.contains("Поступление средств"));
        assertTrue(text.contains("+ 250"));
        assertTrue(text.contains("Raud"));
        assertTrue(text.contains("4 550"));
        assertLiteralOnly(screen);
    }

    // ---------------------------------------------------------------- activity

    @Test
    void activityScreenShowsDurationsAndState() {
        ActivityInfo info = new ActivityInfo(VIEWER, 9000, 7200, 1800, "2026-W32",
                3600, false, 0, "minecraft:overworld");
        MutableComponent screen = MoneyCommand.activityScreen("ru_ru", info, EconomySettings.WeeklyFund.defaults());
        String text = compiled(screen);
        assertTrue(text.contains("Ваша активность"));
        assertTrue(text.contains("2 ч"));
        assertTrue(text.contains("активны"));
        assertLiteralOnly(screen);
    }

    // ---------------------------------------------------------------- duration

    @Test
    void durationFormatsLocalizedUnits() {
        assertEquals("1 д 2 ч", MoneyCommand.duration("ru_ru", 86_400 + 7_200));
        assertEquals("30 мин", MoneyCommand.duration("ru_ru", 1_800));
        assertEquals("5 д 3 ч 12 мин", MoneyCommand.duration("ru_ru", 5 * 86_400 + 3 * 3600 + 720));
        assertEquals("45 с", MoneyCommand.duration("ru_ru", 45));
        assertEquals("3 ч 12 мин", MoneyCommand.duration("ru_ru", 3 * 3600 + 720));
        assertEquals("1 ч 30 мин", MoneyCommand.duration("ru_ru", 3_600 + 1_800));
    }

    // ---------------------------------------------------------------- weekly

    @Test
    void weeklyScreenForEligiblePlayerShowsShareAndDeadline() {
        WeeklyPlayerInfo info = new WeeklyPlayerInfo("2026-W32", true, null,
                7_200, 3, 10, 5, 15, 120, 0, 0, System.currentTimeMillis() + 86_400_000L);
        MutableComponent screen = MoneyCommand.weeklyScreen("ru_ru", info, "⛃500", "", null);
        String text = compiled(screen);
        assertTrue(text.contains("Недельная награда"));
        assertTrue(text.contains("⛃500"));
        assertTrue(text.contains("Активных дней"));
        assertLiteralOnly(screen);
    }

    @Test
    void weeklyScreenForIneligibleShowsReason() {
        WeeklyPlayerInfo info = new WeeklyPlayerInfo("2026-W32", false, NotEligibleReason.MIN_ACTIVE_DAYS,
                0, 0, 0, 0, 0, 0, 0, 0, 0);
        MutableComponent screen = MoneyCommand.weeklyScreen("ru_ru", info, "", "", "Нужный день");
        String text = compiled(screen);
        assertTrue(text.contains("не подходите"));
        assertTrue(text.contains("Нужный день"));
        assertLiteralOnly(screen);
    }

    // ---------------------------------------------------------------- history

    @Test
    void historyEmptyShowsTitleAndMessage() {
        MutableComponent screen = MoneyCommand.historyEmpty("ru_ru");
        String text = compiled(screen);
        assertTrue(text.contains("История операций"));
        assertTrue(text.contains("Операций пока нет"));
        assertLiteralOnly(screen);
    }

    @Test
    void historyLineMarksIncomingAsIncome() {
        TransactionRow row = row(1, OTHER, VIEWER);
        MutableComponent line = MoneyCommand.historyLine("ru_ru", VIEWER, row, "⛃250");
        String text = compiled(line);
        assertTrue(text.contains("⛃250"), "входящая операция содержит сумму");
        assertTrue(text.startsWith("+"), "входящая операция начинается со знака дохода");
        assertLiteralOnly(line);
        assertTrue(hasHover(line), "у строки истории есть подсказка");
    }

    @Test
    void historyLineMarksOutgoingAsExpense() {
        TransactionRow row = row(1, VIEWER, OTHER);
        MutableComponent line = MoneyCommand.historyLine("ru_ru", VIEWER, row, "⛃250");
        String text = compiled(line);
        assertTrue(text.contains("⛃250"), "исходящая операция содержит сумму");
        assertTrue(text.startsWith("−"), "исходящая операция начинается со знака расхода");
        assertLiteralOnly(line);
    }

    @Test
    void navigationShowsArrowsOnlyWhenNeeded() {
        MutableComponent first = EconomyComponents.navigation("ru_ru", "/money history ",
                "ui.history.back", "ui.history.next", 1, 4);
        assertFalse(compiled(first).contains("Назад"), "на первой странице нет кнопки «назад»");
        assertTrue(compiled(first).contains("Вперёд"));

        MutableComponent middle = EconomyComponents.navigation("ru_ru", "/money history ",
                "ui.history.back", "ui.history.next", 2, 4);
        assertTrue(compiled(middle).contains("Назад"));
        assertTrue(compiled(middle).contains("Вперёд"));

        MutableComponent last = EconomyComponents.navigation("ru_ru", "/money history ",
                "ui.history.back", "ui.history.next", 4, 4);
        assertTrue(compiled(last).contains("Назад"));
        assertFalse(compiled(last).contains("Вперёд"), "на последней странице нет кнопки «вперёд»");
    }

    // ---------------------------------------------------------------- style

    @Test
    void themeColorsAreDistinctHex() {
        var colors = List.of(EconomyTheme.GOLD, EconomyTheme.SUCCESS, EconomyTheme.EXPENSE,
                EconomyTheme.ERROR, EconomyTheme.WARNING, EconomyTheme.INFO, EconomyTheme.SPECIAL,
                EconomyTheme.TEXT, EconomyTheme.MUTED, EconomyTheme.VALUE);
        for (int i = 0; i < colors.size(); i++) {
            for (int j = i + 1; j < colors.size(); j++) {
                assertFalse(colors.get(i).getValue() == colors.get(j).getValue(),
                        "цвета отличаются: " + colors.get(i) + " / " + colors.get(j));
            }
        }
    }

    /**
     * Строки истории не содержат переводов-ключей равно как и голых «<ключ>»
     * — компоненты собираются из уже переведённого текста.
     */
    private static boolean hasHover(Component component) {
        if (component.getStyle() != null && component.getStyle().getHoverEvent() != null) {
            return true;
        }
        if (component.getSiblings() != null) {
            for (Component child : component.getSiblings()) {
                if (hasHover(child)) {
                    return true;
                }
            }
        }
        return false;
    }
}