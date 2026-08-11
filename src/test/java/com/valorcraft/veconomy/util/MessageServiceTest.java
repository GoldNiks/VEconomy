package com.valorcraft.veconomy.util;

import com.valorcraft.veconomy.api.TransactionType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageServiceTest {

    @AfterEach
    void resetLocalizedTables() {
        MessageService.resetTables();
    }

    // ---------------------------------------------------------------- normalizeLocale

    @Test
    void normalizesRussianLocales() {
        assertEquals("ru_ru", MessageService.normalizeLocale("ru_RU"));
        assertEquals("ru_ru", MessageService.normalizeLocale("ru-ru"));
        assertEquals("ru_ru", MessageService.normalizeLocale("RU_ru"));
        assertEquals("ru_ru", MessageService.normalizeLocale("ru"));
    }

    @Test
    void normalizesEnglishLocales() {
        assertEquals("en_us", MessageService.normalizeLocale("en_US"));
        assertEquals("en_us", MessageService.normalizeLocale("en_GB"));
        assertEquals("en_us", MessageService.normalizeLocale("en"));
    }

    @Test
    void fallsBackToDefaultForUnknownOrBlank() {
        assertEquals("ru_ru", MessageService.normalizeLocale("de_DE"));
        assertEquals("ru_ru", MessageService.normalizeLocale("zn-CN"));
        assertEquals("ru_ru", MessageService.normalizeLocale(null));
        assertEquals("ru_ru", MessageService.normalizeLocale(""));
        assertEquals("ru_ru", MessageService.normalizeLocale("   "));
    }

    // ---------------------------------------------------------------- rendering

    @Test
    void formatsBasicArguments() {
        assertEquals("Ваш баланс: ⛃123",
                MessageService.text("ru_ru", "cmd.balance.self", "⛃123"));
        assertEquals("История операций (страница 2 из 5)",
                MessageService.text("ru_ru", "cmd.history.title", 2, 5));
        assertEquals("Вы отправили ⛃50 игроку Steve",
                MessageService.text("ru_ru", "cmd.pay.sent", "⛃50", "Steve"));
    }

    @Test
    void escapesPercentAndUsesNumericArgs() {
        assertEquals("Баланс игрока Steve: ⛃100%",
                MessageService.text("ru_ru", "cmd.balance.other", "Steve", "⛃100%"));
        assertEquals("История операций (страница 30 из 5)",
                MessageService.text("ru_ru", "cmd.history.title", 30, 5));
        MessageService.installTable("ru_ru", Map.of(
                "tpl.percent", "Прогресс: %%",
                "tpl.pos", "%2$d после %1$s"));
        assertEquals("Прогресс: %", MessageService.text("ru_ru", "tpl.percent"));
        assertEquals("7 после 5", MessageService.text("ru_ru", "tpl.pos", "5", 7));
    }

    @Test
    void rendersEnglishLocale() {
        assertEquals("Your balance: ⛃123",
                MessageService.text("en_us", "cmd.balance.self", "⛃123"));
    }

    @Test
    void unknownLocaleFallsBackToRussian() {
        assertEquals("Ваш баланс: ⛃1", MessageService.text("de_DE", "cmd.balance.self", "⛃1"));
    }

    @Test
    void missingKeyReturnsVisibleFallbackText() {
        assertEquals("Не удалось отобразить сообщение.", MessageService.text("ru_ru", "no.such.key.anywhere"));
        assertEquals("Unable to display the message.", MessageService.text("en_us", "no.such.key.anywhere"));
    }

    @Test
    void missingKeyInPrimaryFallsBackToRussian() {
        MessageService.installTable("en_us", Map.of("unrelated.key", "x"));
        assertEquals("Ваш баланс: ⛃5",
                MessageService.text("en_us", "cmd.balance.self", "⛃5"));
    }

    // ---------------------------------------------------------------- nested components

    @Test
    void flattensNestedTranslatableArgument() {
        Component translated = MessageService.message("ru_ru", "cmd.history.title", 1, 3);
        assertEquals("История операций (страница 1 из 3)", MessageService.plainText("ru_ru",
                "cmd.history.title", 1, 3));
        assertEquals("История операций (страница 1 из 3)", compiled(translated));
    }

    @Test
    void buildComponentHasNoTranslatableContents() {
        Component message = MessageService.message("ru_ru", "cmd.pay.sent", "⛃50", "Steve");
        assertLiteralOnly(message);
    }

    private static String compiled(Component component) {
        StringBuilder builder = new StringBuilder();
        MessageServiceTest.appendCompiled(component, builder);
        return builder.toString();
    }

    private static void appendCompiled(Component component, StringBuilder builder) {
        if (component.getContents() instanceof LiteralContents literal) {
            builder.append(literal.text());
        } else if (component.getContents() instanceof TranslatableContents) {
            builder.append("<key>");
        }
        if (component.getSiblings() != null) {
            component.getSiblings().forEach(sibling -> appendCompiled(sibling, builder));
        }
    }

    private static void assertLiteralOnly(Component component) {
        if (component.getContents() instanceof TranslatableContents) {
            throw new AssertionError("Message contains untranslated translatable contents");
        }
        if (component.getSiblings() != null) {
            component.getSiblings().forEach(MessageServiceTest::assertLiteralOnly);
        }
    }

    @Test
    void nestedComponentArgumentIsFlattenedToText() {
        String result = MessageService.plainText("ru_ru", "cmd.duration.days", 3);
        assertEquals("3д", result);
    }

    // ---------------------------------------------------------------- keys

    @Test
    void hasKeyReflectsLoadedTables() {
        assertTrue(MessageService.hasKey("ru_ru", "cmd.balance.self"));
        assertTrue(MessageService.hasKey("en_us", "cmd.balance.self"));
        assertFalse(MessageService.hasKey("ru_ru", "no.such.key"));
    }

    @Test
    void ruAndEnKeySetsMatch() {
        Set<String> ru = MessageService.keys("ru_ru");
        Set<String> en = MessageService.keys("en_us");
        assertEquals(ru.size(), en.size());
        assertTrue(ru.containsAll(en));
        assertTrue(en.containsAll(ru));
    }

    @Test
    void everyTransactionTypeHasLabelInBothLocales() {
        Set<String> ru = MessageService.keys("ru_ru");
        Set<String> en = MessageService.keys("en_us");
        for (TransactionType type : TransactionType.values()) {
            String key = "type." + type.name();
            assertTrue(ru.contains(key), "нет ключа " + key + " в ru_ru");
            assertTrue(en.contains(key), "нет ключа " + key + " в en_us");
            assertFalse(MessageService.text("ru_ru", key).contains("Не удалось отобразить"),
                    "перевод " + key + " не должен падать в fallback");
        }
    }
}