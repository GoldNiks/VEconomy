package com.valorcraft.veconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.api.AccountStatus;
import com.valorcraft.veconomy.api.BalanceSnapshot;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.audit.EconomyStatistics;
import com.valorcraft.veconomy.config.EconomyConfig;
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
                                .executes(EconomyAdminCommand::reload))));
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
            source.sendSuccess(() -> Component.translatable("admin.reload.done").withStyle(ChatFormatting.GREEN), true);
        } catch (Exception e) {
            source.sendFailure(Component.translatable("admin.reload.failed").withStyle(ChatFormatting.RED));
        }
        return 1;
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
