package com.valorcraft.veconomy.integration.ftbquests;

import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import dev.architectury.event.EventResult;
import dev.ftb.mods.ftbquests.events.CustomRewardEvent;
import dev.ftb.mods.ftbquests.quest.reward.CustomReward;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Интеграция с FTB Quests: награда деньгами через встроенный тип «Custom Reward».
 * <p>
 * Ограничения FTB Quests не позволяют серверному моду добавлять собственный тип награды
 * (клиент без такого типа падает при синхронизации квестов), поэтому используется
 * штатное событие {@link CustomRewardEvent}, которое генерируется при получении любой
 * «Кастомной награды».
 * <p>
 * Правило: сумма берётся из названия награды — первое число в заголовке.
 * Примеры названий: {@code 500}, {@code 1500 монет}, {@code Вознаграждение 2500}.
 * Если числа в заголовке нет — награда игнорируется (это обычная кастомная награда).
 * <p>
 * Двойное начисление исключено идемпотентным ключом, привязанным к id награды.
 */
public final class FTBQuestsIntegration {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");

    private static volatile boolean registered;

    private FTBQuestsIntegration() {}

    /** Зарегистрировать слушатель (вызывается один раз при загрузке мода). */
    public static void register() {
        if (registered) {
            return;
        }
        CustomRewardEvent.EVENT.register(FTBQuestsIntegration::onCustomReward);
        registered = true;
        VEconomyMod.LOGGER.info("Интеграция FTB Quests активна (награды деньгами через Custom Reward)");
    }

    private static EventResult onCustomReward(CustomRewardEvent event) {
        if (!EconomyCore.isStarted()) {
            return EventResult.pass();
        }
        CustomReward reward = event.getReward();
        ServerPlayer player = event.getPlayer();
        if (player == null || reward == null) {
            return EventResult.pass();
        }

        long amountMinor = parseAmount(reward.getRawTitle(), EconomyCore.settings().decimalPlaces);
        if (amountMinor <= 0) {
            return EventResult.pass();
        }

        UUID playerId = player.getUUID();
        TransactionContext context = TransactionContext.of(
                TransactionType.QUEST_REWARD,
                playerId,
                "ftbquests:" + reward.getRawTitle(),
                "ftbquests:reward:" + reward.id);
        TransactionResult result = EconomyCore.api().deposit(playerId, amountMinor, context);

        if (result.status() == TransactionResult.Status.SUCCESS
                || result.status() == TransactionResult.Status.DUPLICATE_OPERATION) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "notify.quest.reward", EconomyCore.formatter().format(amountMinor))
                    .withStyle(net.minecraft.ChatFormatting.GREEN));
        }
        return EventResult.pass();
    }

    /** Извлечь сумму (в минимальных единицах) из названия награды; -1 если числа нет. */
    static long parseAmount(String title, int decimalPlaces) {
        if (title == null) {
            return -1;
        }
        Matcher matcher = NUMBER.matcher(title);
        if (!matcher.find()) {
            return -1;
        }
        String token = matcher.group();
        double value;
        try {
            value = Double.parseDouble(token.replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
        if (value <= 0) {
            return -1;
        }
        long divisor = 1;
        for (int i = 0; i < decimalPlaces; i++) {
            divisor *= 10;
        }
        double minor = value * divisor;
        if (minor > Long.MAX_VALUE) {
            return -1;
        }
        long minorLong = Math.round(minor);
        return minorLong > 0 ? minorLong : -1;
    }
}
