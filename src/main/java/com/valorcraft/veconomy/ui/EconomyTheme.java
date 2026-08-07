package com.valorcraft.veconomy.ui;

import net.minecraft.network.chat.TextColor;

/**
 * Единая палитра VEconomy. Все цвета — HEX из {@link TextColor#fromRgb(int)},
 * без {@code ChatFormatting}: текстура задаётся компонентами, а не форматированием.
 */
public final class EconomyTheme {

    /** Деньги, заголовки. */
    public static final TextColor GOLD = TextColor.fromRgb(0xF2C14E);
    /** Доход и успешные действия. */
    public static final TextColor SUCCESS = TextColor.fromRgb(0x63D471);
    /** Расходы. */
    public static final TextColor EXPENSE = TextColor.fromRgb(0xFFB454);
    /** Ошибки. */
    public static final TextColor ERROR = TextColor.fromRgb(0xFF6B6B);
    /** Предупреждения. */
    public static final TextColor WARNING = TextColor.fromRgb(0xFFD166);
    /** Информационные значения. */
    public static final TextColor INFO = TextColor.fromRgb(0x6CB4EE);
    /** Милстоуны и особые награды. */
    public static final TextColor SPECIAL = TextColor.fromRgb(0xB88CFF);
    /** Основной текст. */
    public static final TextColor TEXT = TextColor.fromRgb(0xE8E8E8);
    /** Пояснения и даты. */
    public static final TextColor MUTED = TextColor.fromRgb(0x9A9A9A);
    /** Имена и важные значения. */
    public static final TextColor VALUE = TextColor.fromRgb(0xFFFFFF);

    private EconomyTheme() {}
}
