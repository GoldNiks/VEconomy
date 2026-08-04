package com.valorcraft.veconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.api.BalanceSnapshot;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.persistence.TransactionRow;
import com.valorcraft.veconomy.util.CurrencyParser;
import com.valorcraft.veconomy.util.PlayerResolver;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Пользовательские команды: /money, /balance, /bal, /money pay, /money history.
 * Права: обычные игроки (уровень 0).
 */
public final class MoneyCommand {

    private static final int HISTORY_PAGE_SIZE = 10;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneOffset.UTC);

    private MoneyCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("money")
                .executes(MoneyCommand::balanceSelf)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                context.getSource().getOnlinePlayerNames(), builder))
                        .requires(source -> source.hasPermission(2))
                        .executes(MoneyCommand::balanceOther))
                .then(Commands.literal("pay")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        context.getSource().getOnlinePlayerNames(), builder))
                                .then(Commands.argument("amount", StringArgumentType.string())
                                        .executes(MoneyCommand::pay))))
                .then(Commands.literal("history")
                        .executes(context -> history(context, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> history(context, IntegerArgumentType.getInteger(context, "page"))))));

        dispatcher.register(Commands.literal("balance").executes(MoneyCommand::balanceSelf));
        dispatcher.register(Commands.literal("bal").executes(MoneyCommand::balanceSelf));
    }

    private static int balanceSelf(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            UUID uuid = player.getUUID();
            long balance = EconomyCore.accounts().getBalance(uuid);
            String formatted = EconomyCore.formatter().format(balance);
            MutableComponent message = Component.translatable("cmd.balance.self", formatted)
                    .withStyle(ChatFormatting.GREEN);
            message.append(Component.literal(" (" + EconomyCore.formatter().plural(balance) + ")"));
            source.sendSuccess(() -> message, false);
            source.sendSuccess(() -> Component.translatable("cmd.balance.hint")
                    .withStyle(ChatFormatting.GRAY), false);
        } catch (Exception e) {
            source.sendFailure(Component.translatable("cmd.only.players").withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static int balanceOther(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String playerInput = StringArgumentType.getString(context, "player");
        PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), playerInput);
        if (!target.exists()) {
            source.sendFailure(Component.translatable("error.player.notfound", playerInput)
                    .withStyle(ChatFormatting.RED));
            return 1;
        }
        long balance = EconomyCore.accounts().getBalance(target.uuid());
        source.sendSuccess(() -> Component.translatable("cmd.balance.other",
                target.name(), EconomyCore.formatter().format(balance))
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    static int pay(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer sender = source.getPlayerOrException();
            String targetInput = StringArgumentType.getString(context, "player");
            long amountMinor;
            try {
                amountMinor = CurrencyParser.parse(
                        StringArgumentType.getString(context, "amount"),
                        EconomyCore.settings().decimalPlaces);
            } catch (CurrencyParser.InvalidAmount e) {
                source.sendFailure(Component.translatable("error.invalid.amount").withStyle(ChatFormatting.RED));
                return 1;
            }

            PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), targetInput);
            if (!target.exists()) {
                source.sendFailure(Component.translatable("error.player.notfound", targetInput)
                        .withStyle(ChatFormatting.RED));
                return 1;
            }
            if (sender.getUUID().equals(target.uuid())) {
                source.sendFailure(Component.translatable("cmd.pay.self").withStyle(ChatFormatting.RED));
                return 1;
            }

            TransactionContext txContext = TransactionContext.of(
                    TransactionType.PLAYER_TRANSFER, sender.getUUID(), "pay:" + target.name());
            TransactionResult result = EconomyCore.api().transfer(
                    sender.getUUID(), target.uuid(), amountMinor, txContext);

            switch (result.status()) {
                case SUCCESS -> {
                    source.sendSuccess(() -> Component.translatable("cmd.pay.sent",
                            EconomyCore.formatter().format(amountMinor), target.name())
                            .withStyle(ChatFormatting.GREEN), false);
                    if (target.player() != null) {
                        target.player().sendSystemMessage(Component.translatable("cmd.pay.received",
                                EconomyCore.formatter().format(amountMinor), sender.getGameProfile().getName())
                                .withStyle(ChatFormatting.GREEN));
                    }
                }
                case DUPLICATE_OPERATION -> source.sendSuccess(() -> Component.translatable("cmd.pay.sent",
                        EconomyCore.formatter().format(amountMinor), target.name())
                        .withStyle(ChatFormatting.GREEN), false);
                case INSUFFICIENT_FUNDS -> source.sendFailure(
                        Component.translatable("error.insufficient").withStyle(ChatFormatting.RED));
                case COOLDOWN_ACTIVE -> source.sendFailure(
                        Component.translatable("cmd.pay.cooldown").withStyle(ChatFormatting.RED));
                case LIMIT_EXCEEDED -> source.sendFailure(
                        Component.translatable("error.limit").withStyle(ChatFormatting.RED));
                case ACCOUNT_DISABLED -> source.sendFailure(
                        Component.translatable("error.frozen").withStyle(ChatFormatting.RED));
                case TRANSFERS_DISABLED -> source.sendFailure(
                        Component.translatable("cmd.pay.disabled").withStyle(ChatFormatting.RED));
                case INVALID_AMOUNT -> source.sendFailure(
                        Component.translatable("error.invalid.amount").withStyle(ChatFormatting.RED));
                case RECIPIENT_NOT_FOUND, ACCOUNT_NOT_FOUND -> source.sendFailure(
                        Component.translatable("error.player.notfound", targetInput).withStyle(ChatFormatting.RED));
                default -> source.sendFailure(
                        Component.translatable("error.internal").withStyle(ChatFormatting.RED));
            }
        } catch (Exception e) {
            source.sendFailure(Component.translatable("cmd.only.players").withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static int history(CommandContext<CommandSourceStack> context, int page) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            UUID uuid = player.getUUID();
            List<TransactionRow> rows = EconomyCore.ledger().history(uuid, page, HISTORY_PAGE_SIZE);
            long total = EconomyCore.ledger().countAll();
            int totalPages = Math.max(1, (int) ((total + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE));
            int currentPage = Math.max(1, Math.min(page, totalPages));

            source.sendSuccess(() -> Component.translatable("cmd.history.title", currentPage, totalPages)
                    .withStyle(ChatFormatting.GOLD), false);

            if (rows.isEmpty()) {
                source.sendSuccess(() -> Component.translatable("cmd.history.empty")
                        .withStyle(ChatFormatting.GRAY), false);
                return 1;
            }

            for (TransactionRow row : rows) {
                source.sendSuccess(() -> formatHistoryLine(uuid, row), false);
            }
        } catch (Exception e) {
            source.sendFailure(Component.translatable("cmd.only.players").withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static Component formatHistoryLine(UUID viewerUuid, TransactionRow row) {
        boolean incoming = row.targetUuid() != null && row.targetUuid().equals(viewerUuid)
                && (row.sourceUuid() == null || !row.sourceUuid().equals(viewerUuid));
        String sign = incoming ? "+" : "-";
        ChatFormatting color = incoming ? ChatFormatting.GREEN : ChatFormatting.RED;

        MutableComponent line = Component.empty();
        line.append(Component.literal(TIME.format(Instant.ofEpochMilli(row.createdAt())) + " ")
                .withStyle(ChatFormatting.DARK_GRAY));
        line.append(Component.translatable("type." + row.type().name()).withStyle(ChatFormatting.AQUA));
        line.append(Component.literal(" " + sign + EconomyCore.formatter().format(row.amountMinor()))
                .withStyle(color));
        String counterparty = counterparty(row, viewerUuid);
        if (counterparty != null) {
            line.append(Component.literal(" " + counterparty).withStyle(ChatFormatting.GRAY));
        }
        String reason = row.reason();
        if (reason != null && !reason.isBlank()) {
            line.append(Component.literal(" (" + reason + ")").withStyle(ChatFormatting.DARK_GRAY));
        }
        return line;
    }

    private static String counterparty(TransactionRow row, UUID viewerUuid) {
        if (row.sourceUuid() != null && row.targetUuid() != null) {
            return row.sourceUuid().equals(viewerUuid) ? "\u2192" : "\u2190";
        }
        return null;
    }
}
