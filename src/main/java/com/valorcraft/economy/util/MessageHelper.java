package com.valorcraft.economy.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/** Парсит legacy-строки с кодами форматирования § в styled Component. */
public final class MessageHelper {

    public static Component parse(String legacy) {
        MutableComponent result = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            if (c == '§' && i + 1 < legacy.length()) {
                if (current.length() > 0) {
                    result.append(Component.literal(current.toString()).withStyle(style));
                    current.setLength(0);
                }
                ChatFormatting format = ChatFormatting.getByCode(legacy.charAt(i + 1));
                if (format != null) {
                    if (format == ChatFormatting.RESET) {
                        style = Style.EMPTY;
                    } else if (format.isColor()) {
                        style = style.withColor(TextColor.fromLegacyFormat(format));
                    } else {
                        style = style.applyFormat(format);
                    }
                }
                i++;
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            result.append(Component.literal(current.toString()).withStyle(style));
        }
        return result;
    }

    private MessageHelper() {}
}
