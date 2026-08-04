package com.valorcraft.economy.util;

import com.valorcraft.economy.config.EconomyConfig;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Форматирование и округление денежных сумм по конфигу. */
public final class MoneyFormatter {

    public static String format(double amount) {
        int places = EconomyConfig.DECIMAL_PLACES.get();
        String pattern = "###,##0" + (places > 0 ? "." + "0".repeat(places) : "");
        DecimalFormat format = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US));
        return EconomyConfig.CURRENCY_SYMBOL.get() + format.format(amount);
    }

    public static double round(double amount) {
        int places = EconomyConfig.DECIMAL_PLACES.get();
        double factor = Math.pow(10, places);
        return Math.round(amount * factor) / factor;
    }

    private MoneyFormatter() {}
}
