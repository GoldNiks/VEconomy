package com.valorcraft.veconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.activity.ActivityService.ActivityInfo;
import com.valorcraft.veconomy.activity.WeeklyFundService.NotEligibleReason;
import com.valorcraft.veconomy.activity.WeeklyFundService.WeeklyPlayerInfo;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.integration.permissions.PermissionBridge;
import com.valorcraft.veconomy.persistence.TransactionRow;
import com.valorcraft.veconomy.util.CurrencyParser;
import com.valorcraft.veconomy.util.MessageService;
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
                        .requires(source -> PermissionBridge.has(source,
                                "veconomy.command.balance.other", 2))
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
                                .executes(context -> history(context, IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.literal("activity")
                        .executes(MoneyCommand::activity))
                .then(Commands.literal("weekly")
                        .executes(MoneyCommand::weekly)));

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
            MutableComponent message = MessageService.message(source, "cmd.balance.self", formatted)
                    .withStyle(ChatFormatting.GREEN);
            message.append(Component.literal(" (" + EconomyCore.formatter().plural(balance) + ")"));
            source.sendSuccess(() -> message, false);
            source.sendSuccess(() -> MessageService.message(source, "cmd.balance.hint")
                    .withStyle(ChatFormatting.GRAY), false);
        } catch (Exception e) {
            source.sendFailure(MessageService.message(source, "cmd.only.players").withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static int balanceOther(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String playerInput = StringArgumentType.getString(context, "player");
        PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), playerInput);
        if (!target.exists()) {
            source.sendFailure(MessageService.message(source, "error.player.notfound", playerInput)
                    .withStyle(ChatFormatting.RED));
            return 1;
        }
        long balance = EconomyCore.accounts().getBalance(target.uuid());
        source.sendSuccess(() -> MessageService.message(source, "cmd.balance.other",
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
                source.sendFailure(MessageService.message(source, "error.invalid.amount").withStyle(ChatFormatting.RED));
                return 1;
            }

            PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), targetInput);
            if (!target.exists()) {
                source.sendFailure(MessageService.message(source, "error.player.notfound", targetInput)
                        .withStyle(ChatFormatting.RED));
                return 1;
            }
            if (sender.getUUID().equals(target.uuid())) {
                source.sendFailure(MessageService.message(source, "cmd.pay.self").withStyle(ChatFormatting.RED));
                return 1;
            }

            TransactionContext txContext = TransactionContext.of(
                    TransactionType.PLAYER_TRANSFER, sender.getUUID(), "pay:" + target.name());
            TransactionResult result = EconomyCore.api().transfer(
                    sender.getUUID(), target.uuid(), amountMinor, txContext);

            switch (result.status()) {
                case SUCCESS -> {
                    source.sendSuccess(() -> MessageService.message(source, "cmd.pay.sent",
                            EconomyCore.formatter().format(amountMinor), target.name())
                            .withStyle(ChatFormatting.GREEN), false);
                    if (target.player() != null) {
                        target.player().sendSystemMessage(MessageService.message(target.player(), "cmd.pay.received",
                                EconomyCore.formatter().format(amountMinor), sender.getGameProfile().getName())
                                .withStyle(ChatFormatting.GREEN));
                    }
                }
                case DUPLICATE_OPERATION -> source.sendSuccess(() -> MessageService.message(source, "cmd.pay.sent",
                        EconomyCore.formatter().format(amountMinor), target.name())
                        .withStyle(ChatFormatting.GREEN), false);
                case INSUFFICIENT_FUNDS -> source.sendFailure(
                        MessageService.message(source, "error.insufficient").withStyle(ChatFormatting.RED));
                case COOLDOWN_ACTIVE -> source.sendFailure(
                        MessageService.message(source, "cmd.pay.cooldown").withStyle(ChatFormatting.RED));
                case LIMIT_EXCEEDED -> source.sendFailure(
                        MessageService.message(source, "error.limit").withStyle(ChatFormatting.RED));
                case ACCOUNT_DISABLED -> source.sendFailure(
                        MessageService.message(source, "error.frozen").withStyle(ChatFormatting.RED));
                case TRANSFERS_DISABLED -> source.sendFailure(
                        MessageService.message(source, "cmd.pay.disabled").withStyle(ChatFormatting.RED));
                case INVALID_AMOUNT -> source.sendFailure(
                        MessageService.message(source, "error.invalid.amount").withStyle(ChatFormatting.RED));
                case RECIPIENT_NOT_FOUND, ACCOUNT_NOT_FOUND -> source.sendFailure(
                        MessageService.message(source, "error.player.notfound", targetInput).withStyle(ChatFormatting.RED));
                default -> source.sendFailure(
                        MessageService.message(source, "error.internal").withStyle(ChatFormatting.RED));
            }
        } catch (Exception e) {
            source.sendFailure(MessageService.message(source, "cmd.only.players").withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static int activity(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            ActivityInfo info = EconomyCore.activity().info(player.getUUID()).orElse(null);
            source.sendSuccess(() -> MessageService.message(source, "cmd.activity.title")
                    .withStyle(ChatFormatting.GOLD), false);
            if (info == null) {
                source.sendSuccess(() -> MessageService.message(source, "cmd.activity.empty")
                        .withStyle(ChatFormatting.GRAY), false);
                return 1;
            }
            source.sendSuccess(() -> MessageService.message(source, "cmd.activity.online",
                    formatDuration(info.totalOnlineSeconds())), false);
            source.sendSuccess(() -> MessageService.message(source, "cmd.activity.active",
                    formatDuration(info.totalActiveSeconds())), false);
            source.sendSuccess(() -> MessageService.message(source, "cmd.activity.afk",
                    formatDuration(info.totalAfkSeconds())), false);
            source.sendSuccess(() -> MessageService.message(source, "cmd.activity.week",
                    info.currentWeekId(), formatDuration(info.weeklyActiveSeconds())), false);
            String state = info.afkNow() ? "cmd.activity.state.afk" : "cmd.activity.state.active";
            source.sendSuccess(() -> MessageService.message(source, state)
                    .withStyle(info.afkNow() ? ChatFormatting.RED : ChatFormatting.GREEN), false);
        } catch (Exception e) {
            source.sendFailure(MessageService.message(source, "cmd.only.players").withStyle(ChatFormatting.RED));
        }
        return 1;
    }

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

    /** Длительность в локализованных единицах (для команд игрока). */
    private static String localizedDuration(CommandSourceStack source, long totalSeconds) {
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        StringBuilder builder = new StringBuilder();
        boolean any = false;
        if (days > 0) {
            builder.append(MessageService.text(source, "cmd.duration.days", days));
            any = true;
        }
        if (hours > 0) {
            if (any) {
                builder.append(" ");
            }
            builder.append(MessageService.text(source, "cmd.duration.hours", hours));
            any = true;
        }
        if (minutes > 0) {
            if (any) {
                builder.append(" ");
            }
            builder.append(MessageService.text(source, "cmd.duration.minutes", minutes));
            any = true;
        }
        if (!any) {
            builder.append(MessageService.text(source, "cmd.duration.seconds", seconds));
        }
        return builder.toString();
    }

    private static int weekly(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            WeeklyPlayerInfo info = EconomyCore.weeklyFund().playerWeekly(player.getUUID());
            long untilEnd = Math.max(0, info.weekEndMillis() - System.currentTimeMillis());
            source.sendSuccess(() -> MessageService.message(source, "cmd.weekly.title")
                    .withStyle(ChatFormatting.GOLD), false);
            if (info.eligible()) {
                source.sendSuccess(() -> MessageService.message(source, "cmd.weekly.activeTime",
                        localizedDuration(source, info.activeSeconds())), false);
                source.sendSuccess(() -> MessageService.message(source, "cmd.weekly.activeDays",
                        info.activeDays()), false);
                source.sendSuccess(() -> MessageService.message(source, "cmd.weekly.share",
                        EconomyCore.formatter().format(info.projectedShare()))
                        .withStyle(ChatFormatting.AQUA), false);
                source.sendSuccess(() -> MessageService.message(source, "cmd.weekly.untilEnd",
                        localizedDuration(source, untilEnd)), false);
            } else {
                if (info.reason() != NotEligibleReason.FORECAST_UNAVAILABLE) {
                    source.sendSuccess(() -> MessageService.message(source, "cmd.weekly.notEligible")
                            .withStyle(ChatFormatting.RED), false);
                }
                source.sendSuccess(() -> reasonLine(source, info), false);
            }
            if (info.lastWeekAccrued() > 0) {
                source.sendSuccess(() -> MessageService.message(source, "cmd.weekly.lastWeek",
                        EconomyCore.formatter().format(info.lastWeekAccrued()))
                        .withStyle(ChatFormatting.DARK_GRAY), false);
                long autoPayoutAt = info.lastWeekAutoPayoutAt();
                if (autoPayoutAt > 0 && autoPayoutAt > System.currentTimeMillis()) {
                    source.sendSuccess(() -> MessageService.message(source, "cmd.weekly.payoutSoon",
                            localizedDuration(source, autoPayoutAt - System.currentTimeMillis()))
                            .withStyle(ChatFormatting.GRAY), false);
                }
            }
        } catch (Exception e) {
            source.sendFailure(MessageService.message(source, "cmd.only.players").withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    /** Конкретная причина неучастия: сколько именно не хватает до ближайшего порога. */
    private static MutableComponent reasonLine(CommandSourceStack source, WeeklyPlayerInfo info) {
        EconomySettings.WeeklyFund cfg = EconomyCore.settings().weeklyFund;
        return switch (info.reason()) {
            case MIN_ACTIVE_SECONDS -> MessageService.message(source, "cmd.weekly.reason.minActive",
                    localizedDuration(source, Math.max(0, cfg.minActiveSeconds - info.activeSeconds())))
                    .withStyle(ChatFormatting.GRAY);
            case MIN_ACTIVE_DAYS -> MessageService.message(source,
                    minDaysKey(Math.max(0, cfg.minActiveDays - info.activeDays())),
                    Math.max(0, cfg.minActiveDays - info.activeDays())).withStyle(ChatFormatting.GRAY);
            case NO_POINTS -> {
                if (!cfg.timePointLevels.isEmpty()) {
                    long missing = cfg.timePointLevels.get(0).activeSeconds() - info.activeSeconds();
                    if (missing > 0) {
                        yield MessageService.message(source, "cmd.weekly.reason.noPoints",
                                localizedDuration(source, missing)).withStyle(ChatFormatting.GRAY);
                    }
                }
                yield MessageService.message(source, "cmd.weekly.reason.noPointsNone")
                        .withStyle(ChatFormatting.GRAY);
            }
            case FORECAST_UNAVAILABLE -> MessageService.message(source, "cmd.weekly.reason.unavailable")
                    .withStyle(ChatFormatting.GRAY);
            case WEEKLY_FUND_DISABLED -> MessageService.message(source, "cmd.weekly.reason.disabled")
                    .withStyle(ChatFormatting.GRAY);
            case EXCLUDED -> MessageService.message(source, "cmd.weekly.reason.excluded")
                    .withStyle(ChatFormatting.GRAY);
            case ACCOUNT_FROZEN -> MessageService.message(source, "cmd.weekly.reason.frozen")
                    .withStyle(ChatFormatting.GRAY);
            case MIN_ACCOUNT_AGE -> MessageService.message(source, "cmd.weekly.reason.minAge")
                    .withStyle(ChatFormatting.GRAY);
        };
    }

    /** Ключ множественного числа «дней» (русская плюрализация; en использует один текст). */
    private static String minDaysKey(int count) {
        int mod10 = count % 10;
        int mod100 = count % 100;
        if (mod10 == 1 && mod100 != 11) {
            return "cmd.weekly.reason.minDays.one";
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return "cmd.weekly.reason.minDays.few";
        }
        return "cmd.weekly.reason.minDays.many";
    }

    private static int history(CommandContext<CommandSourceStack> context, int page) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            UUID uuid = player.getUUID();
            long total = EconomyCore.ledger().countForPlayer(uuid);
            int totalPages = Math.max(1, (int) ((total + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE));
            int currentPage = Math.max(1, Math.min(page, totalPages));
            List<TransactionRow> rows = EconomyCore.ledger().history(uuid, currentPage, HISTORY_PAGE_SIZE);

            source.sendSuccess(() -> MessageService.message(source, "cmd.history.title", currentPage, totalPages)
                    .withStyle(ChatFormatting.GOLD), false);

            if (rows.isEmpty()) {
                source.sendSuccess(() -> MessageService.message(source, "cmd.history.empty")
                        .withStyle(ChatFormatting.GRAY), false);
                return 1;
            }

            for (TransactionRow row : rows) {
                source.sendSuccess(() -> formatHistoryLine(source, uuid, row), false);
            }
        } catch (Exception e) {
            source.sendFailure(MessageService.message(source, "cmd.only.players").withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static Component formatHistoryLine(CommandSourceStack source, UUID viewerUuid, TransactionRow row) {
        boolean incoming = row.targetUuid() != null && row.targetUuid().equals(viewerUuid)
                && (row.sourceUuid() == null || !row.sourceUuid().equals(viewerUuid));
        String sign = incoming ? "+" : "-";
        ChatFormatting color = incoming ? ChatFormatting.GREEN : ChatFormatting.RED;

        MutableComponent line = Component.empty();
        line.append(Component.literal(TIME.format(Instant.ofEpochMilli(row.createdAt())) + " ")
                .withStyle(ChatFormatting.DARK_GRAY));
        line.append(MessageService.message(source, "type." + row.type().name()).withStyle(ChatFormatting.AQUA));
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