package com.valorcraft.veconomy.integration.ftbquests;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuestRewardAmountParserTest {
    @Test
    void acceptsBareAndCurrencyPrefixedAmounts() {
        assertEquals(50, QuestRewardAmountParser.parse("50", "⛃", 0));
        assertEquals(50, QuestRewardAmountParser.parse("⛃50", "⛃", 0));
        assertEquals(2550, QuestRewardAmountParser.parse("⛃ 25,50", "⛃", 2));
    }

    @Test
    void rejectsTextWrongSymbolsAndNonPositiveAmounts() {
        assertEquals(-1, QuestRewardAmountParser.parse("Награда 50", "⛃", 0));
        assertEquals(-1, QuestRewardAmountParser.parse("$50", "⛃", 0));
        assertEquals(-1, QuestRewardAmountParser.parse("0", "⛃", 0));
        assertEquals(-1, QuestRewardAmountParser.parse(null, "⛃", 0));
    }
}
