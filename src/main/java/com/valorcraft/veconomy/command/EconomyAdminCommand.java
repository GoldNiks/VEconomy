package com.valorcraft.veconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.activity.WeekId;
import com.valorcraft.veconomy.api.AccountStatus;
import com.valorcraft.veconomy.api.BalanceSnapshot;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.audit.EconomyStatistics;
import com.valorcraft.veconomy.config.EconomyConfig;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.economy.TreasuryService;
import com.valorcraft.veconomy.integration.permissions.PermissionBridge;
import com.valorcraft.veconomy.util.CurrencyParser;
import com.valorcraft.veconomy.util.PlayerResolver;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Optional;
import java.util.UUID;

/**
 * Административные команды экономики: {@code /economy admin ...}. Уровень прав 4.
 * В командах, меняющих баланс, причина обязательна.
 */
public final class EconomyAdminCommand {

    private EconomyAdminCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> players =
                (context, builder) -> SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder);
        dispatcher.register(Commands.literal("economy")
                .then(Commands.literal("admin")
                        .requires(source -> PermissionBridge.has(source, "veconomy.command.admin", 4))
                        .then(Commands.literal("balance")
                                .then(Commands.literal("get")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .executes(EconomyAdminCommand::balanceGet)))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .then(Commands.argument("amount", StringArgumentType.string())
                                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                                .executes(EconomyAdminCommand::balanceAdd)))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .then(Commands.argument("amount", StringArgumentType.string())
                                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                                .executes(EconomyAdminCommand::balanceRemove)))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .then(Commands.argument("amount", StringArgumentType.string())
                                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                                .executes(EconomyAdminCommand::balanceSet))))))
                        .then(Commands.literal("stats")
                                .executes(EconomyAdminCommand::stats))
                        .then(Commands.literal("reload")
                                .executes(EconomyAdminCommand::reload))
                        .then(Commands.literal("weekly")
                                .then(Commands.literal("status")
                                        .executes(EconomyAdminCommand::weeklyStatus))
                                .then(Commands.literal("preview")
                                        .executes(EconomyAdminCommand::weeklyPreview))
                                .then(Commands.literal("run")
                                        .executes(EconomyAdminCommand::weeklyRunPrompt)
                                        .then(Commands.literal("confirm")
                                                .executes(EconomyAdminCommand::weeklyRunConfirm))))));
    }

    private static int balanceGet(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(),
                StringArgumentType.getString(context, "player"));
        if (!target.exists()) {
            return notFound(source, StringArgumentType.getString(context, "player"));
        }
        Optional<BalanceSnapshot> account = EconomyCore.accounts().getAccount(target.uuid());
        if (account.isEmpty()) {
            source.sendFailure(Component.translatable("admin.balance.none", target.name())
                    .withStyle(ChatFormatting.RED));
            return 1;
        }
        BalanceSnapshot snapshot = account.get();
        MutableComponent message = Component.translatable("admin.balance.get",
                target.name(), EconomyCore.formatter().format(snapshot.balanceMinor()))
                .withStyle(ChatFormatting.GREEN);
        message.append(Component.literal(" (" + snapshot.status().name() + ")").withStyle(ChatFormatting.GRAY));
        source.sendSuccess(() -> message, false);
        return 1;
    }

    private static int balanceAdd(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        return mutate(context, true);
    }

    private static int balanceRemove(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        return mutate(context, false);
    }

    private static int mutate(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, boolean add) {
        CommandSourceStack source = context.getSource();
        String playerInput = StringArgumentType.getString(context, "player");
        long amount;
        try {
            amount = CurrencyParser.parse(StringArgumentType.getString(context, "amount"),
                    EconomyCore.settings().decimalPlaces);
        } catch (CurrencyParser.InvalidAmount e) {
            return invalidAmount(source);
        }
        String reason = StringArgumentType.getString(context, "reason");
        if (reason.isBlank()) {
            return invalidReason(source);
        }
        PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), playerInput);
        if (!target.exists()) {
            return notFound(source, playerInput);
        }
        if (target.uuid().equals(TreasuryService.TREASURY_UUID)) {
            source.sendFailure(Component.translatable("admin.balance.treasury").withStyle(ChatFormatting.RED));
            return 1;
        }
        UUID actor = source.getEntity() instanceof net.minecraft.world.entity.player.Player p
                ? p.getUUID() : null;
        TransactionContext contextTx = TransactionContext.of(
                add ? TransactionType.ADMIN_DEPOSIT : TransactionType.ADMIN_WITHDRAW,
                actor, reason);
        TransactionResult result = add
                ? EconomyCore.accounts().deposit(target.uuid(), amount, contextTx)
                : EconomyCore.accounts().withdraw(target.uuid(), amount, contextTx);

        switch (result.status()) {
            case SUCCESS -> {
                String verb = add ? "admin.balance.added" : "admin.balance.removed";
                source.sendSuccess(() -> Component.translatable(verb, target.name(),
                        EconomyCore.formatter().format(amount),
                        EconomyCore.formatter().format(EconomyCore.accounts().getBalance(target.uuid())))
                        .withStyle(ChatFormatting.GREEN), true);
                broadcastAdminChange(source,
                        Component.translatable(add ? "notify.admin.added" : "notify.admin.removed",
                                EconomyCore.formatter().format(amount), target.name())
                                .withStyle(ChatFormatting.GOLD));
            }
            case INSUFFICIENT_FUNDS -> source.sendFailure(
                    Component.translatable("error.insufficient").withStyle(ChatFormatting.RED));
            case LIMIT_EXCEEDED -> source.sendFailure(
                    Component.translatable("error.limit").withStyle(ChatFormatting.RED));
            case ACCOUNT_DISABLED -> source.sendFailure(
                    Component.translatable("error.frozen").withStyle(ChatFormatting.RED));
            case INVALID_AMOUNT -> invalidAmount(source);
            case ACCOUNT_NOT_FOUND -> notFound(source, playerInput);
            default -> source.sendFailure(
                    Component.translatable("error.internal").withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static int balanceSet(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String playerInput = StringArgumentType.getString(context, "player");
        long amount;
        try {
            amount = CurrencyParser.parse(StringArgumentType.getString(context, "amount"),
                    EconomyCore.settings().decimalPlaces);
        } catch (CurrencyParser.InvalidAmount e) {
            return invalidAmount(source);
        }
        String reason = StringArgumentType.getString(context, "reason");
        if (reason.isBlank()) {
            return invalidReason(source);
        }
        PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), playerInput);
        if (!target.exists()) {
            return notFound(source, playerInput);
        }
        if (target.uuid().equals(TreasuryService.TREASURY_UUID)) {
            source.sendFailure(Component.translatable("admin.balance.treasury").withStyle(ChatFormatting.RED));
            return 1;
        }
        UUID actor = source.getEntity() instanceof net.minecraft.world.entity.player.Player p
                ? p.getUUID() : null;
        TransactionResult result = EconomyCore.accounts().setBalance(target.uuid(), amount,
                TransactionContext.of(TransactionType.ADMIN_SET_ADJUSTMENT, actor, reason));
        switch (result.status()) {
            case SUCCESS -> {
                source.sendSuccess(() -> Component.translatable("admin.balance.set",
                        target.name(), EconomyCore.formatter().format(amount)).withStyle(ChatFormatting.GREEN), true);
                broadcastAdminChange(source,
                        Component.translatable("notify.admin.set",
                                target.name(), EconomyCore.formatter().format(amount))
                                .withStyle(ChatFormatting.GOLD));
            }
            case ACCOUNT_NOT_FOUND -> notFound(source, playerInput);
            case ACCOUNT_DISABLED -> source.sendFailure(
                    Component.translatable("error.frozen").withStyle(ChatFormatting.RED));
            case LIMIT_EXCEEDED -> source.sendFailure(
                    Component.translatable("error.limit").withStyle(ChatFormatting.RED));
            default -> source.sendFailure(
                    Component.translatable("error.internal").withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static int stats(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        EconomyStatistics.Stats stats = EconomyCore.statistics().compute();
        MutableComponent header = Component.translatable("admin.stats.title").withStyle(ChatFormatting.GOLD);
        source.sendSuccess(() -> header, false);
        source.sendSuccess(() -> Component.translatable("admin.stats.supply",
                EconomyCore.formatter().format(stats.totalMoneySupply())).withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.translatable("admin.stats.players",
                EconomyCore.formatter().format(stats.playerMoney())).withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.translatable("admin.stats.treasury",
                EconomyCore.formatter().format(stats.treasuryBalance())).withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.translatable("admin.stats.escrow",
                EconomyCore.formatter().format(stats.escrowBalance())).withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.translatable("admin.stats.accounts",
                stats.accountCount()).withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.translatable("admin.stats.median",
                EconomyCore.formatter().format(stats.medianBalance())).withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.translatable("admin.stats.max",
                EconomyCore.formatter().format(stats.maxBalance())).withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.translatable("admin.stats.transfers",
                stats.transferCount(), EconomyCore.formatter().format(stats.transferVolume()))
                .withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.translatable("admin.stats.emission",
                EconomyCore.formatter().format(stats.emissionDay()),
                EconomyCore.formatter().format(stats.emissionWeek()),
                EconomyCore.formatter().format(stats.emissionTotal())).withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int reload(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            EconomyConfig.reload();
            EconomyCore.applySettings(EconomyConfig.toSettings());
            if (net.minecraftforge.fml.ModList.get().isLoaded("ftbquests")) {
                com.valorcraft.veconomy.integration.ftbquests.FTBQuestsIntegration.reloadRewards();
            }
            source.sendSuccess(() -> Component.translatable("admin.reload.done").withStyle(ChatFormatting.GREEN), true);
        } catch (Exception e) {
            source.sendFailure(Component.translatable("admin.reload.failed").withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static int weeklyStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        com.valorcraft.veconomy.activity.WeeklyFundService.WeeklyStatus status =
                EconomyCore.weeklyFund().status();
        source.sendSuccess(() -> Component.translatable("admin.weekly.status.header",
                status.currentWeek()).withStyle(ChatFormatting.GOLD), false);
        String enabled = status.enabled() ? "admin.yes" : "admin.no";
        String autoRun = status.autoRun() ? "admin.yes" : "admin.no";
        source.sendSuccess(() -> Component.translatable("admin.weekly.status.enabled",
                Component.translatable(enabled)), false);
        source.sendSuccess(() -> Component.translatable("admin.weekly.status.autorun",
                Component.translatable(autoRun)), false);
        source.sendSuccess(() -> Component.translatable("admin.weekly.status.amount",
                EconomyCore.formatter().format(status.weeklyAmount())).withStyle(ChatFormatting.YELLOW), false);
        if (status.distributedWeek() != null) {
            source.sendSuccess(() -> Component.translatable("admin.weekly.status.distributed",
                    status.distributedWeek()).withStyle(ChatFormatting.YELLOW), false);
        } else {
            source.sendSuccess(() -> Component.translatable("admin.weekly.status.init")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        if (status.weekDistributed()) {
            source.sendSuccess(() -> Component.translatable("admin.weekly.status.already")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        source.sendSuccess(() -> Component.translatable("admin.weekly.status.eligible",
                status.eligiblePlayers(), status.totalPoints(),
                formatDuration(status.totalCountedSeconds())).withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.translatable("admin.weekly.status.total",
                EconomyCore.formatter().format(status.totalShare())).withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int weeklyPreview(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        EconomySettings.WeeklyFund cfg = EconomyCore.settings().weeklyFund;
        java.util.List<com.valorcraft.veconomy.activity.WeeklyFundService.WeeklyAllocation> allocations =
                EconomyCore.weeklyFund().preview();
        source.sendSuccess(() -> Component.translatable("admin.weekly.preview.header",
                WeekId.previous(WeekId.current()),
                EconomyCore.formatter().format(cfg.weeklyAmount)).withStyle(ChatFormatting.GOLD), false);
        if (allocations.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("admin.weekly.preview.empty")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        long totalShare = 0;
        int shown = 0;
        for (com.valorcraft.veconomy.activity.WeeklyFundService.WeeklyAllocation allocation : allocations) {
            totalShare += allocation.share();
            if (shown++ >= PREVIEW_LINES) {
                continue;
            }
            String name = PlayerResolver.resolve(source.getServer(), allocation.playerId().toString()).name();
            MutableComponent line = Component.literal(name).withStyle(ChatFormatting.AQUA);
            line.append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY));
            line.append(Component.translatable("admin.weekly.preview.row",
                    EconomyCore.formatter().format(allocation.share()),
                    formatDuration(allocation.countedSeconds()), allocation.points()));
            source.sendSuccess(() -> line, false);
        }
        if (allocations.size() > PREVIEW_LINES) {
            source.sendSuccess(() -> Component.translatable("admin.weekly.preview.more",
                    allocations.size() - PREVIEW_LINES).withStyle(ChatFormatting.GRAY), false);
        }
        source.sendSuccess(() -> Component.translatable("admin.weekly.preview.total",
                allocations.size(), EconomyCore.formatter().format(totalShare),
                EconomyCore.formatter().format(Math.max(0, cfg.weeklyAmount - totalShare)))
                .withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int weeklyRunPrompt(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        EconomySettings.WeeklyFund cfg = EconomyCore.settings().weeklyFund;
        java.util.List<com.valorcraft.veconomy.activity.WeeklyFundService.WeeklyAllocation> allocations =
                EconomyCore.weeklyFund().preview();
        long totalShare = allocations.stream()
                .mapToLong(com.valorcraft.veconomy.activity.WeeklyFundService.WeeklyAllocation::share).sum();
        source.sendSuccess(() -> Component.translatable("admin.weekly.run.prompt",
                WeekId.previous(WeekId.current()),
                EconomyCore.formatter().format(totalShare)).withStyle(ChatFormatting.GOLD), true);
        source.sendSuccess(() -> Component.translatable("admin.weekly.run.hint")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int weeklyRunConfirm(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        java.util.Map<UUID, Long> payments = EconomyCore.weeklyFund().runNow();
        if (payments.isEmpty()) {
            source.sendFailure(Component.translatable("admin.weekly.run.empty")
                    .withStyle(ChatFormatting.RED));
            return 1;
        }
        long total = payments.values().stream().mapToLong(Long::longValue).sum();
        source.sendSuccess(() -> Component.translatable("admin.weekly.run.done",
                payments.size(), EconomyCore.formatter().format(total)).withStyle(ChatFormatting.GREEN), true);
        if (EconomyCore.settings().weeklyFund.notify && source.getServer() != null) {
            for (net.minecraft.server.level.ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
                Long amount = payments.get(player.getUUID());
                if (amount != null) {
                    player.sendSystemMessage(Component.translatable("notify.weekly.reward",
                            EconomyCore.formatter().format(amount)).withStyle(ChatFormatting.GOLD));
                }
            }
        }
        return 1;
    }

    private static final int PREVIEW_LINES = 20;

    private static String formatDuration(long totalSeconds) {
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            builder.append(days).append("д ");
        }
        if (hours > 0) {
            builder.append(hours).append("ч ");
        }
        if (minutes > 0) {
            builder.append(minutes).append("м ");
        }
        builder.append(seconds).append("с");
        return builder.toString();
    }

    private static int invalidAmount(CommandSourceStack source) {
        source.sendFailure(Component.translatable("error.invalid.amount").withStyle(ChatFormatting.RED));
        return 1;
    }

    /** Разослать уведомление об административном изменении, если включено в конфиге. */
    private static void broadcastAdminChange(CommandSourceStack source, Component message) {
        if (EconomyCore.settings().broadcastAdminChanges
                && source.getServer() != null
                && source.getServer().getPlayerList() != null) {
            source.getServer().getPlayerList().broadcastSystemMessage(message, false);
        }
    }

    private static int invalidReason(CommandSourceStack source) {
        source.sendFailure(Component.translatable("error.reason.required").withStyle(ChatFormatting.RED));
        return 1;
    }

    private static int notFound(CommandSourceStack source, String input) {
        source.sendFailure(Component.translatable("error.player.notfound", input).withStyle(ChatFormatting.RED));
        return 1;
    }
}
