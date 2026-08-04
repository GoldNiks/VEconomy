package com.valorcraft.veconomy.util;

/**
 * Разбор введённой игроком/админом суммы в минимальные единицы валюты.
 * Поддерживает десятичную запись в соответствии с настройкой decimalPlaces
 * (например, при decimalPlaces = 2 строка «10.5» превращается в 1050 минимальных единиц).
 */
public final class CurrencyParser {

    private CurrencyParser() {}

    /** Исключение при некорректном вводе суммы. */
    public static final class InvalidAmount extends RuntimeException {
        public InvalidAmount(String message) {
            super(message);
        }
    }

    /** Распарсить строку в минимальные единицы. Отрицательные значения запрещены. */
    public static long parse(String input, int decimalPlaces) throws InvalidAmount {
        if (input == null || input.isBlank()) {
            throw new InvalidAmount("пустая строка");
        }
        String text = input.trim();
        boolean negative = text.startsWith("-");
        if (negative) {
            throw new InvalidAmount("отрицательная сумма");
        }
        if (text.startsWith("+")) {
            text = text.substring(1);
        }
        int dot = text.indexOf('.');
        int comma = text.indexOf(',');
        int separator = -1;
        if (dot >= 0 && comma >= 0) {
            throw new InvalidAmount("несколько разделителей");
        } else if (dot >= 0) {
            separator = dot;
        } else if (comma >= 0) {
            separator = comma;
        }
        String wholePart;
        String fractionPart = "";
        if (separator >= 0) {
            wholePart = text.substring(0, separator);
            fractionPart = text.substring(separator + 1);
        } else {
            wholePart = text;
        }
        if (wholePart.isEmpty() || !wholePart.chars().allMatch(Character::isDigit)) {
            throw new InvalidAmount("некорректное целое число");
        }
        if (fractionPart.length() > decimalPlaces) {
            throw new InvalidAmount("слишком много десятичных знаков");
        }
        if (!fractionPart.isEmpty() && !fractionPart.chars().allMatch(Character::isDigit)) {
            throw new InvalidAmount("некорректная дробная часть");
        }
        long whole;
        try {
            whole = Long.parseLong(wholePart);
        } catch (NumberFormatException e) {
            throw new InvalidAmount("число слишком велико");
        }
        // нормализуем дробную часть до decimalPlaces разрядов
        String padded = fractionPart + "0".repeat(decimalPlaces - fractionPart.length());
        long fraction = padded.isEmpty() ? 0 : Long.parseLong(padded);
        long divisor = 1;
        for (int i = 0; i < decimalPlaces; i++) {
            divisor *= 10;
        }
        try {
            return Math.addExact(Math.multiplyExact(whole, divisor), fraction);
        } catch (ArithmeticException e) {
            throw new InvalidAmount("число слишком велико");
        }
    }
}
