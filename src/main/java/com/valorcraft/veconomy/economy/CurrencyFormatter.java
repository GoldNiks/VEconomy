package com.valorcraft.veconomy.economy;

import com.valorcraft.veconomy.config.EconomySettings;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Форматирование денежных сумм из минимальных единиц (long) в отображаемый вид
 * с учётом количества десятичных знаков, символа валюты и русского склонения.
 */
public final class CurrencyFormatter {

    private volatile EconomySettings settings;

    public CurrencyFormatter(EconomySettings settings) {
        this.settings = settings;
    }

    public void applySettings(EconomySettings settings) {
        this.settings = settings;
    }

    /** Полная строка: символ + число (например, «⛃1 234»). */
    public String format(long minor) {
        long divisor = settings.displayDivisor();
        long whole = minor / divisor;
        long fraction = minor % divisor;
        DecimalFormat format = new DecimalFormat("#,##0",
                DecimalFormatSymbols.getInstance(Locale.US));
        StringBuilder sb = new StringBuilder(format.format(whole));
        if (settings.decimalPlaces > 0) {
            String frac = Long.toString(fraction);
            sb.append('.');
            sb.append("0".repeat(settings.decimalPlaces - frac.length()));
            sb.append(frac);
        }
        String symbol = settings.currencySymbol;
        return (symbol == null || symbol.isEmpty()) ? sb.toString() : symbol + sb;
    }

    /** Только число, без символа. */
    public String formatAmount(long minor) {
        return format(minor).replace(settings.currencySymbol == null ? "" : settings.currencySymbol, "");
    }

    /** Название валюты с правильным склонением для количества в отображаемых единицах. */
    public String plural(long minor) {
        long display = minorToDisplay(minor);
        return plural(display, settings.currencyNameSingular, settings.currencyNameFew, settings.currencyNameMany);
    }

    /** Полная строка с числом и склонённым названием (например, «1 монета»). */
    public String formatWithName(long minor) {
        return formatAmount(minor).trim() + " " + plural(minor);
    }

    /** Перевести минимальные единицы в отображаемое количество (целое). */
    public long minorToDisplay(long minor) {
        return minor / settings.displayDivisor();
    }

    /** Чистая функция русского склонения (1 монета, 2 монеты, 5 монет, 21 монета). */
    public static String plural(long n, String one, String few, String many) {
        long mod10 = Math.floorMod(n, 10);
        long mod100 = Math.floorMod(n, 100);
        if (mod10 == 1 && mod100 != 11) {
            return one;
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return few;
        }
        return many;
    }
}
