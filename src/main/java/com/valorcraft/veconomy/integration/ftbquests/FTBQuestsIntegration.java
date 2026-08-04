package com.valorcraft.veconomy.integration.ftbquests;

import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import dev.architectury.event.EventResult;
import dev.ftb.mods.ftbquests.events.CustomRewardEvent;
import dev.ftb.mods.ftbquests.events.ObjectCompletedEvent;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.CustomReward;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Интеграция с FTB Quests: награды деньгами.
 * <p>
 * Два механизма:
 * <ol>
 *   <li><b>«Кастомная награда»</b> (встроенный тип) — название награды должно быть
 *       целиком числом (например {@code 500} или {@code 1500,50}). Текст-обёртка не
 *       поддерживается, чтобы случайная кастомная награда не печатала деньги. Клиенту
 *       мод не требуется — отдельный тип награды невозможен без установки мода на клиенте.</li>
 *   <li><b>Автоначисление по главам</b> — при завершении любого квеста игроки команды
 *       получают сумму за квест из таблицы {@link QuestRewardConfig}. Удобно для
 *       нарастающих наград: дальше глава — больше сумма.</li>
 * </ol>
 * Двойное начисление исключено идемпотентными ключами.
 */
public final class FTBQuestsIntegration {

    /** Название «Кастомной награды» должно состоять целиком из числа (без текста). */
    private static final Pattern WHOLE_NUMBER = Pattern.compile("[0-9]+(?:[.,][0-9]+)?");

    private static volatile boolean registered;

    private FTBQuestsIntegration() {}

    /** Зарегистрировать слушатели (вызывается один раз при загрузке мода). */
    public static void register() {
        if (registered) {
            return;
        }
        QuestRewardConfig.load(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
        CustomRewardEvent.EVENT.register(FTBQuestsIntegration::onCustomReward);
        ObjectCompletedEvent.QUEST.register(FTBQuestsIntegration::onQuestCompleted);
        registered = true;
        VEconomyMod.LOGGER.info("Интеграция FTB Quests активна (награды деньгами)");
    }

    /** Перечитать таблицу наград по главам (команда {@code /economy admin reload}). */
    public static void reloadRewards() {
        QuestRewardConfig.load(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
    }

    // ---------------------------------------------------------------- custom reward

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
                "ftbquests:reward:" + reward.id + ":" + playerId);
        TransactionResult result = EconomyCore.api().deposit(playerId, amountMinor, context);

        if (result.status() == TransactionResult.Status.SUCCESS
                || result.status() == TransactionResult.Status.DUPLICATE_OPERATION) {
            player.sendSystemMessage(Component.translatable(
                    "notify.quest.reward", EconomyCore.formatter().format(amountMinor))
                    .withStyle(ChatFormatting.GREEN));
        }
        return EventResult.pass();
    }

    // ---------------------------------------------------------------- auto reward per chapter

    private static EventResult onQuestCompleted(ObjectCompletedEvent.QuestEvent event) {
        if (!EconomyCore.isStarted()) {
            return EventResult.pass();
        }
        Quest quest = event.getQuest();
        if (quest == null) {
            return EventResult.pass();
        }
        Chapter chapter = quest.getQuestChapter();
        long amountMinor = QuestRewardConfig.rewardForChapter(chapter == null ? null : chapter.getRawTitle());
        if (amountMinor <= 0) {
            return EventResult.pass();
        }

        TeamData teamData = event.getData();
        if (teamData == null) {
            return EventResult.pass();
        }
        var teamOpt = teamOf(teamData.getTeamId());
        if (teamOpt.isEmpty()) {
            return EventResult.pass();
        }
        String chapterTitle = chapter == null ? "?" : chapter.getRawTitle();
        long questId = quest.id;
        for (UUID member : teamOpt.get().getMembers()) {
            TransactionResult result = EconomyCore.api().deposit(member, amountMinor,
                    TransactionContext.of(TransactionType.QUEST_REWARD, member,
                            "ftbquests:" + chapterTitle,
                            "ftbquests:quest:" + questId + ":" + member));
            if (result.status() == TransactionResult.Status.SUCCESS) {
                notifyMember(member, amountMinor, chapterTitle);
            }
        }
        return EventResult.pass();
    }

    private static void notifyMember(UUID member, long amount, String chapterTitle) {
        var server = com.valorcraft.veconomy.util.ServerHolder.get();
        if (server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(member);
        if (player != null) {
            player.sendSystemMessage(Component.translatable(
                    "notify.quest.reward", EconomyCore.formatter().format(amount))
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    // ---------------------------------------------------------------- one-time compensation

    /**
     * Разовая компенсация за квесты, пройденные до установки мода. Начисляет каждому
     * игроку сумму за все выполненные квесты по таблице глав. Идемпотентный ключ
     * {@code questcomp:v1:<uuid>} не даёт выдать повторно.
     *
     * @return краткий отчёт о выполнении
     */
    public static String compensatePastQuests() {
        if (!ModList.get().isLoaded("ftbquests") || !EconomyCore.isStarted()) {
            return "НЕ ИНИЦИАЛИЗИРОВАНО";
        }
        try {
            var questFile = dev.ftb.mods.ftbquests.api.FTBQuestsAPI.api().getQuestFile(true);
            if (questFile == null) {
                return "Квестовый файл не найден";
            }
            int paidTeams = 0;
            int paidPlayers = 0;
            long paidTotal = 0;
            var teamManager = dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager();
            for (TeamData teamData : questFile.getAllTeamData()) {
                long teamTotal = 0;
                for (var chapter : questFile.getAllChapters()) {
                    String chapterTitle = chapter.getRawTitle();
                    long perQuest = QuestRewardConfig.rewardForChapter(chapterTitle);
                    if (perQuest <= 0) {
                        continue;
                    }
                    for (Quest quest : chapter.getQuests()) {
                        if (teamData.isCompleted(quest)) {
                            teamTotal += perQuest;
                        }
                    }
                }
                if (teamTotal <= 0) {
                    continue;
                }
                var teamOpt = teamOf(teamData.getTeamId());
                if (teamOpt.isEmpty()) {
                    continue;
                }
                for (UUID member : teamOpt.get().getMembers()) {
                    TransactionResult result = EconomyCore.api().deposit(member, teamTotal,
                            TransactionContext.of(TransactionType.QUEST_REWARD, member,
                                    "compensation:past-quests",
                                    "questcomp:v1:" + member));
                    if (result.status() == TransactionResult.Status.SUCCESS) {
                        paidPlayers++;
                        paidTotal += teamTotal;
                        VEconomyMod.LOGGER.info("Компенсация игроку {}: {} (квесты команды)", member, teamTotal);
                    }
                }
                paidTeams++;
            }
            return "Компенсация завершена: команд " + paidTeams
                    + ", игроков " + paidPlayers + ", сумма " + paidTotal;
        } catch (Throwable t) {
            VEconomyMod.LOGGER.error("Ошибка компенсации за квесты", t);
            return "Ошибка: " + t;
        }
    }

    private static java.util.Optional<? extends dev.ftb.mods.ftbteams.api.Team> teamOf(UUID teamId) {
        try {
            return dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager().getTeamByID(teamId);
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    // ---------------------------------------------------------------- utils

    /**
     * Извлечь сумму (в минимальных единицах) из названия награды; -1 если названия нет
     * или оно не является числом. Строго: весь заголовок должен быть числом, без лишнего
     * текста (например {@code 500} или {@code 1500,50}) — иначе награда игнорируется.
     * Так случайная кастомная награда с числом в тексте не напечатает деньги.
     */
    static long parseAmount(String title, int decimalPlaces) {
        if (title == null) {
            return -1;
        }
        String token = title.trim();
        if (!WHOLE_NUMBER.matcher(token).matches()) {
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
        BigDecimal minor = value.movePointRight(decimalPlaces)
                .setScale(0, RoundingMode.HALF_UP);
        if (minor.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            return -1;
        }
        long minorLong = minor.longValue();
        return minorLong > 0 ? minorLong : -1;
    }
}
