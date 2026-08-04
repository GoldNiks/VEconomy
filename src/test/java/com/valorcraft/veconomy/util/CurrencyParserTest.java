package com.valorcraft.veconomy.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CurrencyParserTest {

    @Test
    void parsesWholeNumbers() {
        assertEquals(1000, CurrencyParser.parse("1000", 0));
        assertEquals(1, CurrencyParser.parse("1", 0));
        assertEquals(0, CurrencyParser.parse("0", 0));
    }

    @Test
    void parsesDecimalsByPlaces() {
        assertEquals(1000, CurrencyParser.parse("10", 2));
        assertEquals(1050, CurrencyParser.parse("10.5", 2));
        assertEquals(105, CurrencyParser.parse("10.5", 1));
        assertEquals(1000, CurrencyParser.parse("10,00", 2));
    }

    @Test
    void rejectsInvalid() {
        assertThrows(CurrencyParser.InvalidAmount.class, () -> CurrencyParser.parse("-5", 0));
        assertThrows(CurrencyParser.InvalidAmount.class, () -> CurrencyParser.parse("abc", 0));
        assertThrows(CurrencyParser.InvalidAmount.class, () -> CurrencyParser.parse("", 0));
        assertThrows(CurrencyParser.InvalidAmount.class, () -> CurrencyParser.parse("1.2.3", 0));
        assertThrows(CurrencyParser.InvalidAmount.class, () -> CurrencyParser.parse("1,2.3", 0));
        assertThrows(CurrencyParser.InvalidAmount.class, () -> CurrencyParser.parse("1.234", 2));
    }

    @Test
    void rejectsOverflow() {
        assertThrows(CurrencyParser.InvalidAmount.class,
                () -> CurrencyParser.parse("99999999999999999999", 0));
    }
}
