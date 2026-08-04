package com.valorcraft.veconomy.economy;

import com.valorcraft.veconomy.config.EconomySettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CurrencyFormatterTest {

    private static final EconomySettings DEFAULT = EconomySettings.defaults();

    @Test
    void formatsWholeCoins() {
        CurrencyFormatter formatter = new CurrencyFormatter(DEFAULT);
        assertEquals("⛃1,000", formatter.format(1000));
        assertEquals("⛃0", formatter.format(0));
        assertEquals("⛃12", formatter.format(12));
    }

    @Test
    void formatsWithDecimalPlaces() {
        EconomySettings twoPlaces = new EconomySettings("coin", "coins", "coins", "$", 2,
                9_000_000_000_000L, true, true, 1, 1_000_000, 2,
                "test.db", 5000, true);
        CurrencyFormatter formatter = new CurrencyFormatter(twoPlaces);
        assertEquals("$10.00", formatter.format(1000));
        assertEquals("$10.50", formatter.format(1050));
    }

    @Test
    void pluralizationRussian() {
        EconomySettings settings = new EconomySettings("монета", "монеты", "монет", "⛃", 0,
                9_000_000_000_000L, true, true, 1, 1_000_000, 2,
                "test.db", 5000, true);
        CurrencyFormatter formatter = new CurrencyFormatter(settings);
        assertEquals("монета", formatter.plural(1));
        assertEquals("монеты", formatter.plural(2));
        assertEquals("монеты", formatter.plural(4));
        assertEquals("монет", formatter.plural(5));
        assertEquals("монет", formatter.plural(11));
        assertEquals("монет", formatter.plural(12));
        assertEquals("монета", formatter.plural(21));
        assertEquals("монеты", formatter.plural(22));
        assertEquals("монета", formatter.plural(101));
    }

    @Test
    void formatWithName() {
        CurrencyFormatter formatter = new CurrencyFormatter(DEFAULT);
        assertEquals("1 монета", formatter.formatWithName(1));
        assertEquals("5 монет", formatter.formatWithName(5));
    }

    @Test
    void staticPluralEdgeCases() {
        assertEquals("монета", CurrencyFormatter.plural(1, "монета", "монеты", "монет"));
        assertEquals("монеты", CurrencyFormatter.plural(2, "монета", "монеты", "монет"));
        assertEquals("монет", CurrencyFormatter.plural(0, "монета", "монеты", "монет"));
        assertEquals("монет", CurrencyFormatter.plural(14, "монета", "монеты", "монет"));
        assertEquals("монета", CurrencyFormatter.plural(121, "монета", "монеты", "монет"));
        assertEquals("монеты", CurrencyFormatter.plural(102, "монета", "монеты", "монет"));
    }
}
