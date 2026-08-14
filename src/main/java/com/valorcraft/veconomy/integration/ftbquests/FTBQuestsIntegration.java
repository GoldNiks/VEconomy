package com.valorcraft.veconomy.integration.ftbquests;

import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.economy.TreasuryService;
import com.valorcraft.veconomy.config.ConfigPaths;
import com.valorcraft.veconomy.util.MessageService;
import dev.architectury.event.EventResult;
import dev.ftb.mods.ftbquests.events.CustomRewardEvent;
import dev.ftb.mods.ftbquests.events.ObjectCompletedEvent;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.CommandReward;
import dev.ftb.mods.ftbquests.quest.reward.CustomReward;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.util.List;
import java.util.UUID;

/**
 * Интеграция с FTB Quests: награды деньгами.
 * <p>
 * Два механизма:
 * <ol>
 *   <li><b>«Кастомная награда»</b> (встроенный тип) — название награды должно быть
 *       целиком числом (например {@code 500} или {@code 1500,50}). Текст-обёртка не
 *       поддерживается, чтобы случайная кастомная награда не печатала деньги. Клиенту
 *       мод не требуется — отдельный тип награды невозможен без установки мода на клиенте.
 *       Можно отключить через {@code customRewardEnabled} в конфиге.</li>
 *   <li><b>Автоначисление по главам</b> — при завершении любого квеста команда получает
 *       фиксированный фонд из таблицы {@link QuestRewardConfig}, который делится между
 *       участниками поровну (остаток — в казну). Размер команды не влияет на эмиссию.</li>
 * </ol>
 * Двойное начисление исключено идемпотентными ключами.
 */
public final class FTBQuestsIntegration {

    /** Название «Кастомной награды» должно состоять целиком из числа (без текста). */
    private static volatile boolean registered;

    private FTBQuestsIntegration() {}

    /** Зарегистрировать слушатели (вызывается один раз при загрузке мода). */
    public static void register() {
        if (registered) {
            return;
        }
        QuestRewardConfig.load(ConfigPaths.directory());
        CustomRewardEvent.EVENT.register(FTBQuestsIntegration::onCustomReward);
        ObjectCompletedEvent.QUEST.register(FTBQuestsIntegration::onQuestCompleted);
        registered = true;
        VEconomyMod.LOGGER.info("Интеграция FTB Quests активна (награды деньгами)");
    }

    /** Перечитать таблицу наград по главам (команда {@code /economy admin reload}). */
    public static void reloadRewards() {
        QuestRewardConfig.load(ConfigPaths.directory());
    }

    // ---------------------------------------------------------------- custom reward

    private static EventResult onCustomReward(CustomRewardEvent event) {
        if (!EconomyCore.isStarted() || !QuestRewardConfig.customRewardEnabled()) {
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

        if (result.status() == TransactionResult.Status.SUCCESS) {
            player.sendSystemMessage(com.valorcraft.veconomy.ui.EconomyComponents.reward(
                    MessageService.text(player, "notify.quest.reward"),
                    EconomyCore.formatter().format(amountMinor)));
        }
        return EventResult.pass();
    }

    // ---------------------------------------------------------------- visible command reward

    public enum CommandRewardStatus {
        SUCCESS,
        ALREADY_DISTRIBUTED,
        NOT_READY,
        INVALID_QUEST_ID,
        QUEST_NOT_FOUND,
        QUEST_NOT_COMPLETED,
        TEAM_NOT_FOUND,
        INVALID_REWARD_SETUP,
        AUTO_REWARD_CONFLICT,
        PARTIAL_FAILURE
    }

    /**
     * Pays the visible amount from the quest's sole monetary Command Reward as a common team fund.
     * The command accepts no amount or target: both are derived from trusted FTB Quests state.
     */
    public static CommandRewardStatus claimVisibleCommandReward(ServerPlayer player, String questCode) {
        if (!EconomyCore.isStarted() || player == null) {
            return CommandRewardStatus.NOT_READY;
        }

        final long questId;
        try {
            questId = QuestObjectBase.parseCodeString(questCode);
        } catch (RuntimeException e) {
            return CommandRewardStatus.INVALID_QUEST_ID;
        }

        try {
            var questFile = dev.ftb.mods.ftbquests.api.FTBQuestsAPI.api().getQuestFile(true);
            if (questFile == null) {
                return CommandRewardStatus.NOT_READY;
            }
            Quest quest = questFile.getQuest(questId);
            if (quest == null) {
                return CommandRewardStatus.QUEST_NOT_FOUND;
            }

            var teamOpt = dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager().getTeamForPlayer(player);
            if (teamOpt.isEmpty()) {
                return CommandRewardStatus.TEAM_NOT_FOUND;
            }
            var team = teamOpt.get();
            TeamData teamData = questFile.getNullableTeamData(team.getId());
            if (teamData == null || !teamData.isCompleted(quest)) {
                return CommandRewardStatus.QUEST_NOT_COMPLETED;
            }

            List<CommandReward> moneyRewards = quest.getRewards().stream()
                    .filter(CommandReward.class::isInstance)
                    .map(CommandReward.class::cast)
                    .filter(reward -> parseVisibleAmount(reward.getRawTitle()) > 0)
                    .toList();
            if (moneyRewards.size() != 1 || !moneyRewards.get(0).isTeamReward()) {
                VEconomyMod.LOGGER.error("FTB Quests: quest {} must have exactly one monetary Command Reward with Team Reward enabled",
                        quest.getCodeString());
                return CommandRewardStatus.INVALID_REWARD_SETUP;
            }

            Chapter chapter = quest.getQuestChapter();
            String chapterTitle = chapter == null ? null : chapter.getRawTitle();
            if (QuestRewardConfig.rewardForQuest(quest.id, chapterTitle) > 0) {
                VEconomyMod.LOGGER.error("FTB Quests: quest {} has both a visible Command Reward and an automatic JSON reward; payout rejected",
                        quest.getCodeString());
                return CommandRewardStatus.AUTO_REWARD_CONFLICT;
            }

            long fundMinor = parseVisibleAmount(moneyRewards.get(0).getRawTitle());
            List<UUID> members = List.copyOf(team.getMembers());
            if (members.isEmpty()) {
                return CommandRewardStatus.TEAM_NOT_FOUND;
            }
            long perMember = fundMinor / members.size();
            long remainder = fundMinor % members.size();
            if (perMember <= 0) {
                VEconomyMod.LOGGER.error("FTB Quests: common fund {} for quest {} is smaller than team size {}",
                        fundMinor, quest.getCodeString(), members.size());
                return CommandRewardStatus.INVALID_REWARD_SETUP;
            }

            String label = chapterTitle == null ? quest.getCodeString() : chapterTitle;
            String operationPrefix = "ftbquests:command:" + quest.getCodeString() + ":" + team.getId();
            int successes = 0;
            int duplicates = 0;
            int failures = 0;
            for (UUID member : members) {
                TransactionResult result = EconomyCore.api().deposit(member, perMember,
                        TransactionContext.of(TransactionType.QUEST_REWARD, member,
                                "ftbquests:" + label,
                                operationPrefix + ":" + member));
                if (result.status() == TransactionResult.Status.SUCCESS) {
                    successes++;
                    notifyMember(member, perMember, label);
                } else if (result.status() == TransactionResult.Status.DUPLICATE_OPERATION) {
                    duplicates++;
                } else {
                    failures++;
                    VEconomyMod.LOGGER.error("FTB Quests: failed to pay quest {} member {}: {}",
                            quest.getCodeString(), member, result.status());
                }
            }
            if (remainder > 0) {
                TransactionResult result = EconomyCore.api().deposit(TreasuryService.TREASURY_UUID, remainder,
                        TransactionContext.of(TransactionType.QUEST_REWARD, null,
                                "ftbquests:" + label + " (remainder)",
                                operationPrefix + ":treasury"));
                if (result.status() == TransactionResult.Status.SUCCESS) {
                    successes++;
                } else if (result.status() == TransactionResult.Status.DUPLICATE_OPERATION) {
                    duplicates++;
                } else {
                    failures++;
                    VEconomyMod.LOGGER.error("FTB Quests: failed to send quest {} remainder to treasury: {}",
                            quest.getCodeString(), result.status());
                }
            }

            if (failures > 0) {
                return CommandRewardStatus.PARTIAL_FAILURE;
            }
            if (successes == 0 && duplicates > 0) {
                return CommandRewardStatus.ALREADY_DISTRIBUTED;
            }
            VEconomyMod.LOGGER.info("FTB Quests: distributed common fund {} for quest {} among {} team members ({} each, {} to treasury)",
                    fundMinor, quest.getCodeString(), members.size(), perMember, remainder);
            return CommandRewardStatus.SUCCESS;
        } catch (Throwable t) {
            VEconomyMod.LOGGER.error("FTB Quests: visible command reward failed for quest {}", questCode, t);
            return CommandRewardStatus.PARTIAL_FAILURE;
        }
    }

    private static long parseVisibleAmount(String title) {
        return QuestRewardAmountParser.parse(title, EconomyCore.settings().currencySymbol,
                EconomyCore.settings().decimalPlaces);
    }

    // ---------------------------------------------------------------- auto reward per chapter

    /**
     * Автоначисление при завершении квеста. Фонд за квест фиксированный и делится между
     * участниками команды поровну (остаток — в казну): команда из N игроков создаёт
     * ровно столько же монет, сколько команда из одного. Приглашение альтов не разгоняет
     * эмиссию.
     */
    private static EventResult onQuestCompleted(ObjectCompletedEvent.QuestEvent event) {
        if (!EconomyCore.isStarted()) {
            return EventResult.pass();
        }
        Quest quest = event.getQuest();
        if (quest == null) {
            return EventResult.pass();
        }
        Chapter chapter = quest.getQuestChapter();
        String chapterTitle = chapter == null ? null : chapter.getRawTitle();
        long fundMinor = QuestRewardConfig.rewardForQuest(quest.id, chapterTitle);
        if (fundMinor <= 0) {
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
        java.util.List<UUID> members = java.util.List.copyOf(teamOpt.get().getMembers());
        if (members.isEmpty()) {
            return EventResult.pass();
        }

        long perMember = fundMinor / members.size();
        long remainder = fundMinor % members.size();
        String label = chapterTitle == null ? "?" : chapterTitle;
        long questId = quest.id;
        if (perMember > 0) {
            for (UUID member : members) {
                TransactionResult result = EconomyCore.api().deposit(member, perMember,
                        TransactionContext.of(TransactionType.QUEST_REWARD, member,
                                "ftbquests:" + label,
                                "ftbquests:quest:" + questId + ":" + member));
                if (result.status() == TransactionResult.Status.SUCCESS) {
                    notifyMember(member, perMember, label);
                }
            }
        }
        if (remainder > 0) {
            EconomyCore.api().deposit(TreasuryService.TREASURY_UUID, remainder,
                    TransactionContext.of(TransactionType.QUEST_REWARD, null,
                            "ftbquests:" + label + " (остаток)",
                            "ftbquests:quest:" + questId + ":treasury"));
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
            player.sendSystemMessage(com.valorcraft.veconomy.ui.EconomyComponents.reward(
                    MessageService.text(player, "notify.quest.reward"),
                    EconomyCore.formatter().format(amount)));
        }
    }

    // ---------------------------------------------------------------- one-time compensation

    /**
     * Разовая компенсация за квесты, пройденные до установки мода. Стоимость выполненных
     * квестов команды — фиксированный фонд, делится между её участниками поровну (остаток —
     * в казну). Идемпотентные ключи {@code questcomp:v1:<uuid>} и
     * {@code questcomp:v1:treasury:<teamId>} не дают выдать повторно.
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
            for (TeamData teamData : questFile.getAllTeamData()) {
                long teamTotal = 0;
                for (var chapter : questFile.getAllChapters()) {
                    String chapterTitle = chapter.getRawTitle();
                    for (Quest quest : chapter.getQuests()) {
                        if (teamData.isCompleted(quest)) {
                            teamTotal += QuestRewardConfig.rewardForQuest(quest.id, chapterTitle);
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
                java.util.List<UUID> members = java.util.List.copyOf(teamOpt.get().getMembers());
                if (members.isEmpty()) {
                    continue;
                }
                long perMember = teamTotal / members.size();
                long remainder = teamTotal % members.size();
                if (perMember > 0) {
                    for (UUID member : members) {
                        TransactionResult result = EconomyCore.api().deposit(member, perMember,
                                TransactionContext.of(TransactionType.QUEST_REWARD, member,
                                        "compensation:past-quests",
                                        "questcomp:v1:" + member));
                        if (result.status() == TransactionResult.Status.SUCCESS) {
                            paidPlayers++;
                            paidTotal += perMember;
                            VEconomyMod.LOGGER.info("Компенсация игроку {}: {} (квесты команды)", member, perMember);
                        }
                    }
                }
                if (remainder > 0) {
                    // Ключ по команде: каждый остаток (из своей команды) попадает в казну
                    // ровно один раз; общий ключ допускал бы только первый остаток.
                    EconomyCore.api().deposit(TreasuryService.TREASURY_UUID, remainder,
                            TransactionContext.of(TransactionType.QUEST_REWARD, null,
                                    "compensation:past-quests:remainder",
                                    "questcomp:v1:treasury:" + teamData.getTeamId()));
                    paidTotal += remainder;
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
        return QuestRewardAmountParser.parse(title, "", decimalPlaces);
    }
}
