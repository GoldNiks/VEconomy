package com.valorcraft.veconomy.integration.ftbquests;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/** Parses the visible FTB Quests reward title into economy minor units. */
final class QuestRewardAmountParser {
    private static final Pattern NUMBER = Pattern.compile("[0-9]+(?:[.,][0-9]+)?");

    private QuestRewardAmountParser() {}

    static long parse(String title, String currencySymbol, int decimalPlaces) {
        if (title == null) {
            return -1;
        }
        String token = title.trim();
        String symbol = currencySymbol == null ? "" : currencySymbol.trim();
        if (!symbol.isEmpty() && token.startsWith(symbol)) {
            token = token.substring(symbol.length()).trim();
        }
        if (!NUMBER.matcher(token).matches()) {
            return -1;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(token.replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
        if (value.signum() <= 0) {
            return -1;
        }
        BigDecimal minor = value.movePointRight(Math.max(0, decimalPlaces))
                .setScale(0, RoundingMode.HALF_UP);
        if (minor.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            return -1;
        }
        long result = minor.longValue();
        return result > 0 ? result : -1;
    }
}
