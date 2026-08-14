package com.valorcraft.veconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.activity.MilestoneService;
import com.valorcraft.veconomy.integration.permissions.PermissionBridge;
import com.valorcraft.veconomy.util.MessageService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.fml.ModList;

import java.util.UUID;

/**
 * Внутренние команды для доверенных интеграций (скрипты, консоль):
 * {@code /economy internal milestone-grant <uuid> <milestoneId> <idempotencyKey>}.
 * Уровень прав 4, ключ обязателен — повторные вызовы идемпотентны.
 */
public final class InternalCommand {

    private InternalCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("economy")
                .then(Commands.literal("internal")
                        .requires(source -> PermissionBridge.has(source, "veconomy.command.internal", 4))
                        .then(Commands.literal("milestone-grant")
                                .then(Commands.argument("uuid", StringArgumentType.word())
                                        .then(Commands.argument("milestone", StringArgumentType.word())
                                                .then(Commands.argument("idempotencyKey", StringArgumentType.greedyString())
                                                        .executes(InternalCommand::milestoneGrant)))))));
        dispatcher.register(Commands.literal("economy")
                .then(Commands.literal("quest-reward")
                        // FTB Command Reward temporarily elevates its player source to permission level 2.
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("quest", StringArgumentType.word())
                                .executes(InternalCommand::questReward))));
    }

    private static int questReward(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ModList.get().isLoaded("ftbquests")) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("FTB Quests is not loaded")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        final net.minecraft.server.level.ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("This command must be run by a player")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        String questCode = StringArgumentType.getString(context, "quest");
        var status = com.valorcraft.veconomy.integration.ftbquests.FTBQuestsIntegration
                .claimVisibleCommandReward(player, questCode);
        if (status == com.valorcraft.veconomy.integration.ftbquests.FTBQuestsIntegration.CommandRewardStatus.SUCCESS
                || status == com.valorcraft.veconomy.integration.ftbquests.FTBQuestsIntegration.CommandRewardStatus.ALREADY_DISTRIBUTED) {
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("VEconomy quest reward: " + status)
                    .withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        source.sendFailure(net.minecraft.network.chat.Component.literal("VEconomy quest reward failed: " + status)
                .withStyle(ChatFormatting.RED));
        return 0;
    }

    private static int milestoneGrant(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        UUID playerId;
        try {
            playerId = UUID.fromString(StringArgumentType.getString(context, "uuid"));
        } catch (IllegalArgumentException e) {
            source.sendFailure(MessageService.message(source, "error.player.notfound",
                    StringArgumentType.getString(context, "uuid")).withStyle(ChatFormatting.RED));
            return 1;
        }
        String milestoneId = StringArgumentType.getString(context, "milestone");
        String key = StringArgumentType.getString(context, "idempotencyKey");
        MilestoneService.MilestoneGrantResult result =
                EconomyCore.milestones().grantExternal(playerId, milestoneId, key);
        String playerName = EconomyCore.accounts().getAccount(playerId)
                .map(a -> a.lastKnownName() != null ? a.lastKnownName() : a.playerId().toString())
                .orElse(playerId.toString());
        switch (result.status()) {
            case GRANTED -> source.sendSuccess(() -> MessageService.message(source, "cmd.internal.milestone.granted",
                    milestoneId, playerName, EconomyCore.formatter().format(result.amountMinor()))
                    .withStyle(ChatFormatting.GREEN), true);
            case ALREADY_CLAIMED -> source.sendSuccess(() ->
                    MessageService.message(source, "cmd.internal.milestone.already", milestoneId, playerName)
                            .withStyle(ChatFormatting.YELLOW), true);
            case NOT_FOUND -> source.sendFailure(
                    MessageService.message(source, "cmd.internal.milestone.notfound", milestoneId)
                            .withStyle(ChatFormatting.RED));
            case EXTERNAL_ONLY -> source.sendFailure(
                    MessageService.message(source, "cmd.internal.milestone.externalonly", milestoneId)
                            .withStyle(ChatFormatting.RED));
            case INVALID_KEY -> source.sendFailure(
                    MessageService.message(source, "cmd.internal.milestone.invalidkey").withStyle(ChatFormatting.RED));
            case DUPLICATE_OPERATION -> source.sendFailure(
                    MessageService.message(source, "cmd.internal.milestone.duplicate").withStyle(ChatFormatting.RED));
            case MILESTONES_DISABLED, DISABLED -> source.sendFailure(
                    MessageService.message(source, "cmd.internal.milestone.disabled").withStyle(ChatFormatting.RED));
            case ACTIVITY_DISABLED -> source.sendFailure(
                    MessageService.message(source, "cmd.internal.milestone.activitydisabled")
                            .withStyle(ChatFormatting.RED));
            case LIMIT_EXCEEDED -> source.sendFailure(
                    MessageService.message(source, "cmd.internal.milestone.limit").withStyle(ChatFormatting.RED));
            case ACCOUNT_FROZEN -> source.sendFailure(
                    MessageService.message(source, "cmd.internal.milestone.frozen").withStyle(ChatFormatting.RED));
            default -> source.sendFailure(
                    MessageService.message(source, "cmd.internal.milestone.error", milestoneId, result.status())
                            .withStyle(ChatFormatting.RED));
        }
        return 1;
    }
}
