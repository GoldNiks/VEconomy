package com.valorcraft.veconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.activity.AccountFlagUpdateResult;
import com.valorcraft.veconomy.activity.RewardExclusionStatus;
import com.valorcraft.veconomy.api.AccountStatus;
import com.valorcraft.veconomy.api.BalanceSnapshot;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.audit.AuditEventRow;
import com.valorcraft.veconomy.audit.EconomyStatistics;
import com.valorcraft.veconomy.audit.ResolutionStatus;
import com.valorcraft.veconomy.audit.SuspicionScanner;
import com.valorcraft.veconomy.config.AuditConfig;
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

import java.util.List;
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
                        .then(Commands.literal("account")
                                .then(Commands.literal("info")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .executes(EconomyAdminCommand::accountInfo)))
                                .then(Commands.literal("freeze")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(context -> accountSetStatus(
                                                                context, AccountStatus.FROZEN)))))
                                .then(Commands.literal("unfreeze")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(context -> accountSetStatus(
                                                                context, AccountStatus.ACTIVE)))))
                                .then(Commands.literal("exclude-rewards")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .executes(context -> accountSetExcluded(context, true))))
                                .then(Commands.literal("include-rewards")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .executes(context -> accountSetExcluded(context, false)))))
                        .then(Commands.literal("stats")
                                .executes(EconomyAdminCommand::stats))
                        .then(Commands.literal("milestone")
                                .then(Commands.literal("list")
                                        .executes(EconomyAdminCommand::milestoneList)
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .executes(EconomyAdminCommand::milestoneListForPlayer)))
                                .then(Commands.literal("check")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .then(Commands.argument("milestone", StringArgumentType.word())
                                                        .suggests(EconomyAdminCommand::suggestMilestones)
                                                        .executes(EconomyAdminCommand::milestoneCheck))))
                                .then(Commands.literal("grant")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .then(Commands.argument("milestone", StringArgumentType.word())
                                                        .suggests(EconomyAdminCommand::suggestMilestones)
                                                        .executes(context -> milestoneGrant(context, null))
                                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                                .executes(context -> milestoneGrant(context,
                                                                        StringArgumentType.getString(context, "reason")))))))
                                .then(Commands.literal("revoke")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .then(Commands.argument("milestone", StringArgumentType.word())
                                                        .suggests(EconomyAdminCommand::suggestMilestones)
                                                        .executes(EconomyAdminCommand::milestoneRevoke)))))
                        .then(Commands.literal("reload")
                                .executes(EconomyAdminCommand::reload))
                        .then(Commands.literal("audit")
                                .then(Commands.literal("list")
                                        .executes(context -> auditList(context, null))
                                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 500))
                                                .executes(context -> auditList(context,
                                                        IntegerArgumentType.getInteger(context, "limit")))))
                                .then(Commands.literal("player")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .executes(context -> auditPlayer(context, null))
                                                .then(Commands.argument("limit",
                                                                IntegerArgumentType.integer(1, 500))
                                                        .executes(context -> auditPlayer(context,
                                                                IntegerArgumentType.getInteger(
                                                                        context, "limit"))))))
                                .then(Commands.literal("signals")
                                        .executes(context -> auditSignals(context, null))
                                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 500))
                                                .executes(context -> auditSignals(context,
                                                        IntegerArgumentType.getInteger(context, "limit")))))
                                .then(Commands.literal("suspicious")
                                        .executes(context -> auditSuspicious(context, null))
                                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 500))
                                                .executes(context -> auditSuspicious(context,
                                                        IntegerArgumentType.getInteger(context, "limit")))))
                                .then(Commands.literal("transaction")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .executes(EconomyAdminCommand::auditEvent)))
                                .then(Commands.literal("resolve")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .executes(context -> auditResolve(context,
                                                        ResolutionStatus.RESOLVED))
                                                .then(Commands.argument("note", StringArgumentType.greedyString())
                                                        .executes(context -> auditResolve(context,
                                                                ResolutionStatus.RESOLVED)))))
                                .then(Commands.literal("dismiss")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .executes(context -> auditResolve(context,
                                                        ResolutionStatus.DISMISSED))
                                                .then(Commands.argument("note", StringArgumentType.greedyString())
                                                        .executes(context -> auditResolve(context,
                                                                ResolutionStatus.DISMISSED)))))
                                .then(Commands.literal("status")
                                        .executes(EconomyAdminCommand::auditStatus))
                                .then(Commands.literal("scan")
                                        .executes(context -> auditScan(context, null))
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .executes(context -> auditScan(context,
                                                        StringArgumentType.getString(context, "player"))))))
                        .then(Commands.literal("weekly")
                                .then(Commands.literal("status")
                                        .executes(EconomyAdminCommand::weeklyStatus))
                                .then(Commands.literal("preview")
                                        .executes(context -> weeklyPreview(context, null))
                                        .then(Commands.argument("week", StringArgumentType.word())
                                                .executes(context -> weeklyPreview(context,
                                                        StringArgumentType.getString(context, "week")))))
                                .then(Commands.literal("run")
                                        .executes(EconomyAdminCommand::weeklyRunPrompt)
                                        .then(Commands.literal("confirm")
                                                .executes(context -> weeklyRunConfirm(context, null)))
                                        .then(Commands.argument("week", StringArgumentType.word())
                                                .then(Commands.literal("confirm")
                                                        .executes(context -> weeklyRunConfirm(context,
                                                                StringArgumentType.getString(context, "week")))))))));
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
                // Событие ADMIN_BALANCE_CHANGE пишется в AccountService в той же
                // транзакции, что и изменение баланса (op/old/new/delta/tx/reason).
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

    // ---------------------------------------------------------------- account

    private static int accountInfo(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String playerInput = StringArgumentType.getString(context, "player");
        PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), playerInput);
        if (!target.exists()) {
            return notFound(source, playerInput);
        }
        Optional<BalanceSnapshot> account = EconomyCore.accounts().getAccount(target.uuid());
        RewardExclusionStatus exclusion = EconomyCore.activity().excludedFromRewards(target.uuid());
        if (account.isEmpty() && exclusion != RewardExclusionStatus.EXCLUDED) {
            source.sendFailure(Component.translatable("admin.balance.none", target.name())
                    .withStyle(ChatFormatting.RED));
            return 1;
        }
        source.sendSuccess(() -> Component.translatable("admin.account.info.title", target.name())
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.translatable("admin.account.info.uuid",
                target.uuid()).withStyle(ChatFormatting.YELLOW), false);
        String balance = account.isPresent()
                ? EconomyCore.formatter().format(account.get().balanceMinor()) : "—";
        String status = account.isPresent() ? account.get().status().name() : "NO_ACCOUNT";
        String excludedText = switch (exclusion) {
            case EXCLUDED -> "admin.yes";
            case NOT_EXCLUDED -> "admin.no";
            case UNKNOWN -> "admin.account.info.excluded.unknown";
        };
        String frozen = account.isPresent() && account.get().status() == AccountStatus.FROZEN
                ? "admin.yes" : "admin.no";
        source.sendSuccess(() -> Component.translatable("admin.account.info.balance", balance)
                .withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.translatable("admin.account.info.status", status)
                .withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.translatable("admin.account.info.frozen",
                Component.translatable(frozen)).withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.translatable("admin.account.info.excluded",
                Component.translatable(excludedText)).withStyle(ChatFormatting.YELLOW), false);
        if (account.isPresent()) {
            source.sendSuccess(() -> Component.translatable("admin.account.info.created",
                    formatTimestamp(account.get().createdAt())).withStyle(ChatFormatting.GRAY), false);
            source.sendSuccess(() -> Component.translatable("admin.account.info.updated",
                    formatTimestamp(account.get().updatedAt())).withStyle(ChatFormatting.GRAY), false);
        }
        EconomyCore.activity().info(target.uuid()).ifPresent(activityInfo -> {
            source.sendSuccess(() -> Component.translatable("admin.account.info.online",
                    formatDuration(activityInfo.totalOnlineSeconds())).withStyle(ChatFormatting.GRAY), false);
            source.sendSuccess(() -> Component.translatable("admin.account.info.active",
                    formatDuration(activityInfo.totalActiveSeconds())).withStyle(ChatFormatting.GRAY), false);
            if (activityInfo.lastDimension() != null) {
                source.sendSuccess(() -> Component.translatable("admin.account.info.dimension",
                        activityInfo.lastDimension()).withStyle(ChatFormatting.GRAY), false);
            }
        });
        return 1;
    }

    private static int accountSetStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                        AccountStatus status) {
        CommandSourceStack source = context.getSource();
        String playerInput = StringArgumentType.getString(context, "player");
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
        TransactionResult result = status == AccountStatus.FROZEN
                ? EconomyCore.accounts().freeze(target.uuid(), reason, actor)
                : EconomyCore.accounts().unfreeze(target.uuid(), reason, actor);
        if (result.isSuccess()) {
            String verb = status == AccountStatus.FROZEN ? "admin.account.frozen" : "admin.account.unfrozen";
            source.sendSuccess(() -> Component.translatable(verb, target.name())
                    .withStyle(ChatFormatting.GREEN), true);
            if (EconomyCore.settings().broadcastAdminChanges
                    && source.getServer() != null && source.getServer().getPlayerList() != null) {
                source.getServer().getPlayerList().broadcastSystemMessage(
                        Component.translatable(status == AccountStatus.FROZEN
                                ? "notify.admin.frozen" : "notify.admin.unfrozen", target.name())
                                .withStyle(ChatFormatting.GOLD), false);
            }
        } else if (result.status() == TransactionResult.Status.ACCOUNT_NOT_FOUND) {
            return notFound(source, playerInput);
        } else {
            source.sendFailure(Component.translatable("error.internal").withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static int accountSetExcluded(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                          boolean excluded) {
        CommandSourceStack source = context.getSource();
        String playerInput = StringArgumentType.getString(context, "player");
        PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), playerInput);
        if (!target.exists()) {
            return notFound(source, playerInput);
        }
        UUID actor = source.getEntity() instanceof net.minecraft.world.entity.player.Player p
                ? p.getUUID() : null;
        AccountFlagUpdateResult update = EconomyCore.activity().setExcludedFromRewards(
                target.uuid(), excluded, actor);
        switch (update.status()) {
            case SUCCESS -> {
                String verb = excluded ? "admin.account.excluded" : "admin.account.included";
                source.sendSuccess(() -> Component.translatable(verb, target.name())
                        .withStyle(ChatFormatting.GREEN), true);
                if (EconomyCore.settings().broadcastAdminChanges
                        && source.getServer() != null && source.getServer().getPlayerList() != null) {
                    source.getServer().getPlayerList().broadcastSystemMessage(
                            Component.translatable(excluded ? "notify.admin.excluded" : "notify.admin.included",
                                    target.name()).withStyle(ChatFormatting.GOLD), false);
                }
            }
            case NO_CHANGES -> source.sendSuccess(() ->
                    Component.translatable("admin.account.exclude.nochange", target.name())
                            .withStyle(ChatFormatting.YELLOW), false);
            case PLAYER_NOT_FOUND -> notFound(source, playerInput);
            case DATABASE_ERROR -> source.sendFailure(
                    Component.translatable("admin.account.exclude.failed", target.name())
                            .withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    // ---------------------------------------------------------------- audit

    private static final int AUDIT_LIMIT_DEFAULT = 20;

    private static int auditList(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                 Integer limit) {
        CommandSourceStack source = context.getSource();
        int effective = limit != null ? limit : AUDIT_LIMIT_DEFAULT;
        List<AuditEventRow> rows = EconomyCore.audit().recent(effective);
        sendAuditRows(source, "admin.audit.list.title", new Object[]{rows.size()}, rows);
        return 1;
    }

    private static int auditPlayer(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                   Integer limit) {
        CommandSourceStack source = context.getSource();
        String playerInput = StringArgumentType.getString(context, "player");
        PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), playerInput);
        if (!target.exists()) {
            return notFound(source, playerInput);
        }
        int effective = limit != null ? limit : AUDIT_LIMIT_DEFAULT;
        List<AuditEventRow> rows = EconomyCore.audit().byPlayer(target.uuid(), effective);
        sendAuditRows(source, "admin.audit.player.title",
                new Object[]{target.name(), rows.size()}, rows);
        return 1;
    }

    private static int auditSignals(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                    Integer limit) {
        CommandSourceStack source = context.getSource();
        int effective = limit != null ? limit : AUDIT_LIMIT_DEFAULT;
        List<AuditEventRow> rows = EconomyCore.audit().signals(effective);
        sendAuditRows(source, "admin.audit.signals.title", new Object[]{rows.size()}, rows);
        return 1;
    }

    private static int auditScan(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                 String playerInput) {
        CommandSourceStack source = context.getSource();
        if (!AuditConfig.settings().enabled()) {
            source.sendFailure(Component.translatable("admin.audit.scan.disabled")
                    .withStyle(ChatFormatting.RED));
            return 1;
        }
        SuspicionScanner.ScanSummary summary;
        String who;
        if (playerInput == null) {
            summary = EconomyCore.audit().scanAll();
            who = null;
        } else {
            PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), playerInput);
            if (!target.exists()) {
                return notFound(source, playerInput);
            }
            summary = EconomyCore.audit().scanPlayer(target.uuid());
            who = target.name();
        }
        if (who == null) {
            source.sendSuccess(() -> Component.translatable("admin.audit.scan.done",
                    summary.spamSignals(), summary.roundTripSignals(),
                    summary.oversizedSignals(), summary.newAccountSignals(),
                    summary.rapidForwardingSignals(), summary.transferLoopSignals(),
                    summary.highPairFrequencySignals(), summary.newAccountConcentrationSignals(),
                    summary.repeatedDestinationSignals())
                    .withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendSuccess(() -> Component.translatable("admin.audit.scan.player.done", who,
                    summary.spamSignals(), summary.roundTripSignals(),
                    summary.oversizedSignals(), summary.newAccountSignals(),
                    summary.rapidForwardingSignals(), summary.transferLoopSignals(),
                    summary.highPairFrequencySignals(), summary.newAccountConcentrationSignals(),
                    summary.repeatedDestinationSignals())
                    .withStyle(ChatFormatting.GREEN), true);
        }
        return 1;
    }

    /** Детали одного события аудита ({@code audit transaction <id>}). */
    private static int auditEvent(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        long id;
        try {
            id = Long.parseLong(StringArgumentType.getString(context, "id"));
        } catch (NumberFormatException e) {
            source.sendFailure(Component.translatable("admin.audit.event.invalid")
                    .withStyle(ChatFormatting.RED));
            return 1;
        }
        Optional<AuditEventRow> row = EconomyCore.audit().event(id);
        if (row.isEmpty()) {
            source.sendFailure(Component.translatable("admin.audit.event.notfound", id)
                    .withStyle(ChatFormatting.RED));
            return 1;
        }
        AuditEventRow event = row.get();
        String playerName = event.playerId() == null
                ? "-" : PlayerResolver.resolve(source.getServer(),
                event.playerId().toString()).name();
        String actorName = event.actorId() == null
                ? "CONSOLE" : PlayerResolver.resolve(source.getServer(),
                event.actorId().toString()).name();
        source.sendSuccess(() -> Component.translatable("admin.audit.event.title", event.id())
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.translatable("admin.audit.event.type",
                event.eventType()).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("admin.audit.event.severity",
                event.severity().name()).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("admin.audit.event.status",
                event.status()).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("admin.audit.event.player",
                playerName).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("admin.audit.event.actor",
                actorName, event.actorType().name()).withStyle(ChatFormatting.GRAY), false);
        if (event.amountMinor() != null) {
            source.sendSuccess(() -> Component.translatable("admin.audit.event.amount",
                    EconomyCore.formatter().format(event.amountMinor())).withStyle(ChatFormatting.GRAY), false);
        }
        source.sendSuccess(() -> Component.translatable("admin.audit.event.time",
                formatTimestamp(event.createdAt())).withStyle(ChatFormatting.GRAY), false);
        if (event.resolvedAt() != null) {
            source.sendSuccess(() -> Component.translatable("admin.audit.event.resolved",
                    formatTimestamp(event.resolvedAt()), event.resolvedBy() == null ? "-" : event.resolvedBy())
                    .withStyle(ChatFormatting.GRAY), false);
        }
        if (event.resolutionNote() != null && !event.resolutionNote().isBlank()) {
            source.sendSuccess(() -> Component.translatable("admin.audit.event.note",
                    event.resolutionNote()).withStyle(ChatFormatting.GRAY), false);
        }
        if (event.details() != null && !event.details().isBlank()) {
            source.sendSuccess(() -> Component.translatable("admin.audit.event.details",
                    event.details()).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    /** Открытые (необработанные) сигналы. */
    private static int auditSuspicious(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                       Integer limit) {
        CommandSourceStack source = context.getSource();
        int effective = limit != null ? limit : AUDIT_LIMIT_DEFAULT;
        List<AuditEventRow> rows = EconomyCore.audit().openSignals(effective);
        sendAuditRows(source, "admin.audit.suspicious.title", new Object[]{rows.size()}, rows);
        return 1;
    }

    /** Обработать событие: {@code resolve} (подтверждено) или {@code dismiss} (ложное). */
    private static int auditResolve(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                    ResolutionStatus status) {
        CommandSourceStack source = context.getSource();
        long id;
        try {
            id = Long.parseLong(StringArgumentType.getString(context, "id"));
        } catch (NumberFormatException e) {
            source.sendFailure(Component.translatable("admin.audit.event.invalid")
                    .withStyle(ChatFormatting.RED));
            return 1;
        }
        String note;
        try {
            note = StringArgumentType.getString(context, "note");
        } catch (IllegalArgumentException e) {
            note = null;
        }
        String resolvedBy;
        if (source.getEntity() instanceof net.minecraft.world.entity.player.Player p) {
            resolvedBy = p.getName().getString();
        } else {
            resolvedBy = "console";
        }
        boolean done = EconomyCore.audit().resolve(id, status, resolvedBy, note);
        if (!done) {
            source.sendFailure(Component.translatable("admin.audit.event.notfound", id)
                    .withStyle(ChatFormatting.RED));
            return 1;
        }
        String key = status == ResolutionStatus.RESOLVED
                ? "admin.audit.resolve.done" : "admin.audit.dismiss.done";
        source.sendSuccess(() -> Component.translatable(key, id).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** Состояние записи аудита: сбои, очередь повтора, счётчики статусов. */
    private static int auditStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        com.valorcraft.veconomy.audit.AuditService.AuditHealth health = EconomyCore.audit().health();
        source.sendSuccess(() -> Component.translatable("admin.audit.status.title")
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.translatable("admin.audit.status.events",
                EconomyCore.audit().count(),
                EconomyCore.audit().countByStatus(ResolutionStatus.OPEN),
                EconomyCore.audit().countByStatus(ResolutionStatus.RESOLVED),
                EconomyCore.audit().countByStatus(ResolutionStatus.DISMISSED))
                .withStyle(ChatFormatting.GRAY), false);
        if (health.failedWrites() == 0 && health.pendingRetries() == 0) {
            source.sendSuccess(() -> Component.translatable("admin.audit.status.ok")
                    .withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendSuccess(() -> Component.translatable("admin.audit.status.writes",
                    health.failedWrites(), health.pendingRetries()).withStyle(ChatFormatting.RED), false);
            if (health.lastError() != null) {
                source.sendSuccess(() -> Component.translatable("admin.audit.status.error",
                        health.lastError()).withStyle(ChatFormatting.DARK_RED), false);
            }
        }
        return 1;
    }

    private static void sendAuditRows(CommandSourceStack source, String titleKey,
                                      Object[] titleArgs, List<AuditEventRow> rows) {
        source.sendSuccess(() -> Component.translatable(titleKey, titleArgs)
                .withStyle(ChatFormatting.GOLD), false);
        if (rows.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("admin.audit.empty")
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        for (AuditEventRow row : rows) {
            ChatFormatting severityColor = switch (row.severity()) {
                case CRITICAL -> ChatFormatting.DARK_RED;
                case SUSPICIOUS -> ChatFormatting.RED;
                case WARNING -> ChatFormatting.YELLOW;
                default -> ChatFormatting.GRAY;
            };
            String playerName = row.playerId() == null
                    ? "-" : PlayerResolver.resolve(source.getServer(),
                    row.playerId().toString()).name();
            MutableComponent line = Component.literal(formatTimestamp(row.createdAt()))
                    .withStyle(ChatFormatting.DARK_GRAY);
            line.append(Component.literal(" [" + row.severity().name().charAt(0) + "] ")
                    .withStyle(severityColor));
            line.append(Component.literal(row.eventType()).withStyle(ChatFormatting.AQUA));
            line.append(Component.literal(" " + playerName).withStyle(ChatFormatting.WHITE));
            if (row.amountMinor() != null) {
                line.append(Component.literal(" " + EconomyCore.formatter().format(row.amountMinor()))
                        .withStyle(ChatFormatting.GREEN));
            }
            if (row.details() != null && !row.details().isBlank()) {
                line.append(Component.literal(" " + row.details()).withStyle(ChatFormatting.GRAY));
            }
            source.sendSuccess(() -> line, false);
        }
    }

    // ---------------------------------------------------------------- milestone

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestMilestones(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                              com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String prefix = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
        for (com.valorcraft.veconomy.activity.MilestoneDefinition def
                : EconomyCore.milestones().definitions()) {
            if (def.id().toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
                builder.suggest(def.id());
            }
        }
        return builder.buildFuture();
    }

    private static int milestoneList(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        var definitions = EconomyCore.milestones().definitions();
        source.sendSuccess(() -> Component.translatable("admin.milestone.list.title",
                definitions.size()).withStyle(ChatFormatting.GOLD), false);
        for (com.valorcraft.veconomy.activity.MilestoneDefinition def : definitions) {
            String amount = EconomyCore.formatter().format(def.amountMinor());
            source.sendSuccess(() -> Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(def.id()).withStyle(ChatFormatting.AQUA))
                    .append(Component.translatable("admin.milestone.list.row",
                            def.type().name().toLowerCase(java.util.Locale.ROOT), amount,
                            def.enabled() ? "+" : "-")), false);
        }
        return 1;
    }

    private static int milestoneListForPlayer(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String playerInput = StringArgumentType.getString(context, "player");
        PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), playerInput);
        if (!target.exists()) {
            return notFound(source, playerInput);
        }
        var claims = EconomyCore.milestones().claims(target.uuid());
        source.sendSuccess(() -> Component.translatable("admin.milestone.claims.title",
                target.name(), claims.size()).withStyle(ChatFormatting.GOLD), false);
        if (claims.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("admin.milestone.claims.empty")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        for (com.valorcraft.veconomy.activity.MilestoneRow claim : claims) {
            String when = java.time.Instant.ofEpochMilli(claim.claimedAt())
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            source.sendSuccess(() -> Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(claim.milestoneId()).withStyle(ChatFormatting.AQUA))
                    .append(Component.translatable("admin.milestone.claims.row",
                            EconomyCore.formatter().format(claim.amountMinor()), when)), false);
        }
        return 1;
    }

    private static int milestoneCheck(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String playerInput = StringArgumentType.getString(context, "player");
        PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), playerInput);
        if (!target.exists()) {
            return notFound(source, playerInput);
        }
        String milestoneId = StringArgumentType.getString(context, "milestone");
        var def = EconomyCore.milestones().definition(milestoneId);
        if (def.isEmpty()) {
            source.sendFailure(Component.translatable("admin.milestone.notfound", milestoneId)
                    .withStyle(ChatFormatting.RED));
            return 1;
        }
        com.valorcraft.veconomy.activity.MilestoneCheckContext ctx =
                EconomyCore.milestones().contextFor(target.uuid(), source.getServer());
        com.valorcraft.veconomy.activity.MilestoneCheckResult result =
                EconomyCore.milestones().checkMilestone(target.uuid(), def.get(), ctx);
        switch (result.status()) {
            case MET -> source.sendSuccess(() -> Component.translatable("admin.milestone.check.met",
                    milestoneId, target.name()).withStyle(ChatFormatting.GREEN), false);
            case NOT_MET -> source.sendSuccess(() -> Component.translatable("admin.milestone.check.notmet",
                    milestoneId, target.name()).withStyle(ChatFormatting.YELLOW), false);
            case BAD_CONFIG -> source.sendSuccess(() -> Component.translatable("admin.milestone.check.badconfig",
                    milestoneId, target.name(),
                    result.reasonKey() == null ? "admin.milestone.reason.badConfig" : result.reasonKey())
                    .withStyle(ChatFormatting.RED), false);
            default -> source.sendSuccess(() -> Component.translatable("admin.milestone.check.unavailable",
                    milestoneId, target.name(),
                    result.reasonKey() == null ? "error.internal" : result.reasonKey())
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int milestoneGrant(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                      String reason) {
        CommandSourceStack source = context.getSource();
        String playerInput = StringArgumentType.getString(context, "player");
        PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), playerInput);
        if (!target.exists()) {
            return notFound(source, playerInput);
        }
        String milestoneId = StringArgumentType.getString(context, "milestone");
        var def = EconomyCore.milestones().definition(milestoneId);
        if (def.isEmpty()) {
            source.sendFailure(Component.translatable("admin.milestone.notfound", milestoneId)
                    .withStyle(ChatFormatting.RED));
            return 1;
        }
        UUID actor = source.getEntity() instanceof net.minecraft.world.entity.player.Player p
                ? p.getUUID() : null;
        String finalReason = reason == null || reason.isBlank()
                ? "admin:milestone:" + milestoneId : reason;
        com.valorcraft.veconomy.activity.MilestoneService.MilestoneGrantResult result =
                EconomyCore.milestones().grant(target.uuid(), def.get(), actor, finalReason,
                        "milestone:" + milestoneId + ":" + target.uuid());
        sendGrantResult(source, milestoneId, target.name(), result);
        return 1;
    }

    private static int milestoneRevoke(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String playerInput = StringArgumentType.getString(context, "player");
        PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), playerInput);
        if (!target.exists()) {
            return notFound(source, playerInput);
        }
        String milestoneId = StringArgumentType.getString(context, "milestone");
        UUID actor = source.getEntity() instanceof net.minecraft.world.entity.player.Player p
                ? p.getUUID() : null;
        if (EconomyCore.milestones().revoke(target.uuid(), milestoneId, actor)) {
            source.sendSuccess(() -> Component.translatable("admin.milestone.revoked",
                    milestoneId, target.name()).withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendFailure(Component.translatable("admin.milestone.revoke.missing",
                    milestoneId, target.name()).withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static void sendGrantResult(CommandSourceStack source, String milestoneId, String playerName,
                                        com.valorcraft.veconomy.activity.MilestoneService.MilestoneGrantResult result) {
        String key;
        switch (result.status()) {
            case GRANTED -> {
                String amount = EconomyCore.formatter().format(result.amountMinor());
                source.sendSuccess(() -> Component.translatable("admin.milestone.granted",
                        milestoneId, playerName, amount).withStyle(ChatFormatting.GREEN), true);
                return;
            }
            case ALREADY_CLAIMED -> key = "admin.milestone.already";
            case DISABLED -> key = "admin.milestone.disabled";
            case MILESTONES_DISABLED -> key = "admin.milestone.milestones.disabled";
            case EXCLUDED -> key = "admin.milestone.excluded";
            case BAD_CONFIG -> key = "admin.milestone.badconfig";
            case ACCOUNT_FROZEN -> key = "error.frozen";
            case LIMIT_EXCEEDED -> key = "error.limit";
            case DUPLICATE_OPERATION -> key = "admin.milestone.duplicate";
            case DATABASE_ERROR, FAILED -> key = "error.internal";
            default -> key = "error.internal";
        }
        String finalKey = key;
        source.sendFailure(Component.translatable(finalKey, milestoneId, playerName)
                .withStyle(ChatFormatting.RED));
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
        source.sendSuccess(() -> Component.translatable("admin.weekly.status.target",
                status.targetWeek()).withStyle(ChatFormatting.YELLOW), false);
        String enabled = status.enabled() ? "admin.yes" : "admin.no";
        String autoPayout = status.autoPayout() ? "admin.yes" : "admin.no";
        source.sendSuccess(() -> Component.translatable("admin.weekly.status.enabled",
                Component.translatable(enabled)), false);
        source.sendSuccess(() -> Component.translatable("admin.weekly.status.autopayout",
                Component.translatable(autoPayout), status.payoutDelayHours()), false);
        source.sendSuccess(() -> Component.translatable("admin.weekly.status.fund",
                EconomyCore.formatter().format(status.fundAmount())).withStyle(ChatFormatting.YELLOW), false);
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
        source.sendSuccess(() -> Component.translatable("admin.weekly.status.payout",
                status.payoutStatus(),
                status.autoPayoutAt() == null ? "-" : formatTimestamp(status.autoPayoutAt()))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int weeklyPreview(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                     String weekId) {
        CommandSourceStack source = context.getSource();
        java.util.List<com.valorcraft.veconomy.activity.WeeklyFundService.WeeklyAllocation> allocations =
                EconomyCore.weeklyFund().preview(weekId);
        // Неделя для заголовка: указанная команде, либо та, что видит сервис по умолчанию.
        com.valorcraft.veconomy.activity.WeeklyFundService.WeeklyStatus status =
                EconomyCore.weeklyFund().status();
        String shownWeek = weekId != null ? weekId : status.targetWeek();
        source.sendSuccess(() -> Component.translatable("admin.weekly.preview.header",
                shownWeek,
                EconomyCore.formatter().format(status.fundAmount())).withStyle(ChatFormatting.GOLD), false);
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
                    formatDuration(allocation.countedSeconds()), allocation.points(),
                    allocation.activeDays()));
            source.sendSuccess(() -> line, false);
        }
        if (allocations.size() > PREVIEW_LINES) {
            source.sendSuccess(() -> Component.translatable("admin.weekly.preview.more",
                    allocations.size() - PREVIEW_LINES).withStyle(ChatFormatting.GRAY), false);
        }
        long finalTotal = totalShare;
        source.sendSuccess(() -> Component.translatable("admin.weekly.preview.total",
                allocations.size(), EconomyCore.formatter().format(finalTotal),
                EconomyCore.formatter().format(Math.max(0, status.fundAmount() - finalTotal)))
                .withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int weeklyRunPrompt(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        EconomySettings.WeeklyFund cfg = EconomyCore.settings().weeklyFund;
        com.valorcraft.veconomy.activity.WeeklyFundService.WeeklyStatus status =
                EconomyCore.weeklyFund().status();
        source.sendSuccess(() -> Component.translatable("admin.weekly.run.prompt",
                status.targetWeek(),
                EconomyCore.formatter().format(status.totalShare())).withStyle(ChatFormatting.GOLD), true);
        source.sendSuccess(() -> Component.translatable("admin.weekly.run.hint")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int weeklyRunConfirm(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                        String weekId) {
        CommandSourceStack source = context.getSource();
        java.util.Map<UUID, Long> payments = weekId == null
                ? EconomyCore.weeklyFund().runNow()
                : EconomyCore.weeklyFund().runNow(weekId);
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

    private static String formatTimestamp(long millis) {
        return java.time.Instant.ofEpochMilli(millis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
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
