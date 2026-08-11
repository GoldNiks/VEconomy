package com.valorcraft.veconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.activity.ActivityService.ActivityInfo;
import com.valorcraft.veconomy.activity.WeeklyFundService.WeeklyPlayerInfo;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.integration.permissions.PermissionBridge;
import com.valorcraft.veconomy.persistence.TransactionRow;
import com.valorcraft.veconomy.ui.EconomyComponents;
import com.valorcraft.veconomy.ui.EconomyTheme;
import com.valorcraft.veconomy.util.CurrencyParser;
import com.valorcraft.veconomy.util.MessageService;
import com.valorcraft.veconomy.util.PlayerResolver;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Пользовательские команды: /money, /balance, /bal, /money pay, /money history,
 * /money activity, /money weekly. Права: обычные игроки (уровень 0).
 * <p>
 * Все экраны собираются из отдельных компонентов единого визуального стиля
 * ({@link EconomyComponents}), переводы — из языковых таблиц {@link MessageService}.
 * Экраны получают уже отформатированные суммы ({{@link com.valorcraft.veconomy.economy.CurrencyFormatter}}
 * вызывается в обработчиках команд), поэтому их можно тестировать без запуска ядра.
 */
public final class MoneyCommand {

    private static final int HISTORY_PAGE_SIZE = 10;
    /** Шаблон времени истории; зона подставляется из недельного фонда (или UTC вне сервера). */
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final String HISTORY_COMMAND = "/money history ";
    private static final String HISTORY_BACK = "ui.history.back";
    private static final String HISTORY_NEXT = "ui.history.next";

    private MoneyCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("money")
                .executes(MoneyCommand::balanceSelf)
                .then(Commands.literal("help")
                        .executes(MoneyCommand::help))
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
                                .executes(context -> history(context,
                                        IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.literal("activity")
                        .executes(MoneyCommand::activity))
                .then(Commands.literal("weekly")
                        .executes(MoneyCommand::weekly)));

        dispatcher.register(Commands.literal("balance").executes(MoneyCommand::balanceSelf));
        dispatcher.register(Commands.literal("bal").executes(MoneyCommand::balanceSelf));
    }

    // ---------------------------------------------------------------- /money, /balance

    /** Справка по доступным командам: {@code /money help}. */
    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> helpScreen(MessageService.locale(source)), false);
        return 1;
    }

    /** Экран справки: команда (инфо-цвет) + краткое описание. */
    static MutableComponent helpScreen(String locale) {
        MutableComponent out = EconomyComponents.header(MessageService.text(locale, "ui.help.title"));
        helpLine(out, locale, "ui.help.cmd.balance", "ui.help.desc.balance");
        helpLine(out, locale, "ui.help.cmd.balance.other", "ui.help.desc.balance.other");
        helpLine(out, locale, "ui.help.cmd.pay", "ui.help.desc.pay");
        helpLine(out, locale, "ui.help.cmd.history", "ui.help.desc.history");
        helpLine(out, locale, "ui.help.cmd.activity", "ui.help.desc.activity");
        helpLine(out, locale, "ui.help.cmd.weekly", "ui.help.desc.weekly");
        return out;
    }

    private static void helpLine(MutableComponent out, String locale, String cmdKey, String descKey) {
        out.append(Component.literal("\n"));
        out.append(EconomyComponents.info(MessageService.text(locale, cmdKey))
                .append(EconomyComponents.colored(" — ", EconomyTheme.MUTED))
                .append(EconomyComponents.muted(MessageService.text(locale, descKey))));
    }

    private static int balanceSelf(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            long balance = EconomyCore.accounts().getBalance(player.getUUID());
            String locale = MessageService.locale(source);
            source.sendSuccess(() -> balanceScreen(locale,
                    EconomyCore.formatter().format(balance),
                    EconomyCore.formatter().plural(balance)), false);
        } catch (Exception e) {
            source.sendFailure(onlyPlayers(source));
        }
        return 1;
    }

    static MutableComponent balanceScreen(String locale, String formattedAmount, String plural) {
        MutableComponent out = EconomyComponents.header(MessageService.text(locale, "ui.balance.title"));
        out.append(Component.literal("\n"));
        out.append(EconomyComponents.money(formattedAmount)
                .append(EconomyComponents.muted(plural == null || plural.isBlank() ? "" : " " + plural)));
        out.append(Component.literal("\n"));
        out.append(EconomyComponents.clickableSuggest(
                MessageService.text(locale, "ui.balance.hint"), "/money history",
                Component.literal(MessageService.text(locale, "ui.balance.hint.hover"))));
        return out;
    }

    private static int balanceOther(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String playerInput = StringArgumentType.getString(context, "player");
        PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), playerInput);
        if (!target.exists()) {
            source.sendFailure(EconomyComponents.error(
                    MessageService.text(source, "error.player.notfound", playerInput)));
            return 1;
        }
        long balance = EconomyCore.accounts().getBalance(target.uuid());
        String locale = MessageService.locale(source);
        source.sendSuccess(() -> balanceOtherScreen(locale, target.name(),
                EconomyCore.formatter().format(balance)), false);
        return 1;
    }

    static MutableComponent balanceOtherScreen(String locale, String playerName, String formattedAmount) {
        MutableComponent out = EconomyComponents.header(
                MessageService.text(locale, "ui.balance.other.title"));
        out.append(Component.literal("\n"));
        out.append(EconomyComponents.value(playerName))
                .append(EconomyComponents.muted("  —  "))
                .append(EconomyComponents.money(formattedAmount));
        return out;
    }

    // ---------------------------------------------------------------- pay

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
                source.sendFailure(EconomyComponents.error(
                        MessageService.text(source, "error.invalid.amount")));
                return 1;
            }

            PlayerResolver.Resolved target = PlayerResolver.resolve(source.getServer(), targetInput);
            if (!target.exists()) {
                source.sendFailure(EconomyComponents.error(
                        MessageService.text(source, "error.player.notfound", targetInput)));
                return 1;
            }
            if (sender.getUUID().equals(target.uuid())) {
                source.sendFailure(EconomyComponents.error(
                        MessageService.text(source, "cmd.pay.self")));
                return 1;
            }

            TransactionContext txContext = TransactionContext.of(
                    TransactionType.PLAYER_TRANSFER, sender.getUUID(), "pay:" + target.name());
            TransactionResult result = EconomyCore.api().transfer(
                    sender.getUUID(), target.uuid(), amountMinor, txContext);

            switch (result.status()) {
                case SUCCESS -> {
                    String locale = MessageService.locale(source);
                    long senderAfter = result.sourceBalanceAfter() >= 0
                            ? result.sourceBalanceAfter()
                            : EconomyCore.accounts().getBalance(sender.getUUID());
                    source.sendSuccess(() -> paySenderScreen(locale,
                            EconomyCore.formatter().format(amountMinor), target.name(),
                            EconomyCore.formatter().format(senderAfter)), false);
                    if (target.player() != null) {
                        long receiverAfter = result.targetBalanceAfter() >= 0
                                ? result.targetBalanceAfter()
                                : EconomyCore.accounts().getBalance(target.uuid());
                        target.player().sendSystemMessage(payReceiverScreen(
                                MessageService.locale(target.player()),
                                EconomyCore.formatter().format(amountMinor),
                                sender.getGameProfile().getName(),
                                EconomyCore.formatter().format(receiverAfter)));
                    }
                }
                case DUPLICATE_OPERATION -> {
                    String locale = MessageService.locale(source);
                    long senderAfter = EconomyCore.accounts().getBalance(sender.getUUID());
                    source.sendSuccess(() -> paySenderScreen(locale,
                            EconomyCore.formatter().format(amountMinor), target.name(),
                            EconomyCore.formatter().format(senderAfter)), false);
                }
                case INSUFFICIENT_FUNDS -> source.sendFailure(EconomyComponents.error(
                        MessageService.text(source, "error.insufficient")));
                case COOLDOWN_ACTIVE -> source.sendFailure(EconomyComponents.error(
                        MessageService.text(source, "cmd.pay.cooldown")));
                case LIMIT_EXCEEDED -> source.sendFailure(EconomyComponents.error(
                        MessageService.text(source, "error.limit")));
                case ACCOUNT_DISABLED -> source.sendFailure(EconomyComponents.error(
                        MessageService.text(source, "error.frozen")));
                case TRANSFERS_DISABLED -> source.sendFailure(EconomyComponents.error(
                        MessageService.text(source, "cmd.pay.disabled")));
                case INVALID_AMOUNT -> source.sendFailure(EconomyComponents.error(
                        MessageService.text(source, "error.invalid.amount")));
                case RECIPIENT_NOT_FOUND, ACCOUNT_NOT_FOUND -> source.sendFailure(
                        EconomyComponents.error(MessageService.text(source,
                                "error.player.notfound", targetInput)));
                default -> source.sendFailure(EconomyComponents.error(
                        MessageService.text(source, "error.internal")));
            }
        } catch (Exception e) {
            source.sendFailure(onlyPlayers(source));
        }
        return 1;
    }

    /** Экран отправителя после успешного перевода. */
    static MutableComponent paySenderScreen(String locale, String amountText,
                                            String recipientName, String newBalanceText) {
        MutableComponent out = EconomyComponents.success(
                MessageService.text(locale, "ui.pay.title"));
        out.append(Component.literal("\n"));
        out.append(EconomyComponents.expense(amountText))
                .append(EconomyComponents.toPlayer(recipientName));
        out.append(Component.literal("\n"));
        out.append(EconomyComponents.entry(
                MessageService.text(locale, "ui.balance.label"),
                EconomyComponents.money(newBalanceText)));
        return out;
    }

    /** Экран получателя при входящем переводе. */
    static MutableComponent payReceiverScreen(String locale, String amountText,
                                              String senderName, String newBalanceText) {
        MutableComponent out = EconomyComponents.header(
                MessageService.text(locale, "ui.pay.received.title"));
        out.append(Component.literal("\n"));
        out.append(EconomyComponents.income(amountText))
                .append(EconomyComponents.fromPlayer(senderName));
        out.append(Component.literal("\n"));
        out.append(EconomyComponents.entry(
                MessageService.text(locale, "ui.balance.label"),
                EconomyComponents.money(newBalanceText)));
        return out;
    }

    // ---------------------------------------------------------------- activity

    private static int activity(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            String locale = MessageService.locale(source);
            if (!EconomyCore.settings().activity.enabled) {
                // Честный экран: учёт отключён, сохранённые цифры не показываются как живые.
                source.sendSuccess(() -> activityDisabledScreen(locale), false);
                return 1;
            }
            ActivityInfo info = EconomyCore.activity().info(player.getUUID()).orElse(null);
            if (info == null) {
                source.sendSuccess(() -> activityEmpty(locale), false);
                return 1;
            }
            source.sendSuccess(() -> activityScreen(locale, info, EconomyCore.settings().weeklyFund), false);
        } catch (Exception e) {
            source.sendFailure(onlyPlayers(source));
        }
        return 1;
    }

    static MutableComponent activityDisabledScreen(String locale) {
        MutableComponent out = EconomyComponents.header(
                MessageService.text(locale, "ui.activity.title"));
        out.append(Component.literal("\n"));
        out.append(EconomyComponents.muted(MessageService.text(locale, "ui.activity.disabled")));
        return out;
    }

    static MutableComponent activityEmpty(String locale) {
        MutableComponent out = EconomyComponents.header(
                MessageService.text(locale, "ui.activity.title"));
        out.append(Component.literal("\n"));
        out.append(EconomyComponents.muted(MessageService.text(locale, "ui.activity.empty")));
        return out;
    }

    static MutableComponent activityScreen(String locale, ActivityInfo info,
                                           EconomySettings.WeeklyFund cfg) {
        MutableComponent out = EconomyComponents.header(
                MessageService.text(locale, "ui.activity.title"));
        out.append(Component.literal("\n"));
        out.append(EconomyComponents.timeEntry(
                MessageService.text(locale, "ui.activity.online"),
                EconomyComponents.info(duration(locale, info.totalOnlineSeconds()))));
        out.append(Component.literal("\n"));
        out.append(EconomyComponents.entry(
                MessageService.text(locale, "ui.activity.active"),
                EconomyComponents.info(duration(locale, info.totalActiveSeconds()))));
        out.append(Component.literal("\n"));
        out.append(EconomyComponents.entry(
                MessageService.text(locale, "ui.activity.afk"),
                EconomyComponents.info(duration(locale, info.totalAfkSeconds()))));
        out.append(Component.literal("\n"));
        out.append(EconomyComponents.entry(
                MessageService.text(locale, "ui.activity.week", info.currentWeekId()),
                EconomyComponents.info(duration(locale, info.weeklyActiveSeconds()))));
        out.append(Component.literal("\n"));
        String stateKey = info.afkNow() ? "ui.activity.state.afk" : "ui.activity.state.active";
        TextColor stateColor = info.afkNow() ? EconomyTheme.EXPENSE : EconomyTheme.SUCCESS;
        out.append(EconomyComponents.entry(
                MessageService.text(locale, "ui.activity.now"),
                EconomyComponents.colored(MessageService.text(locale, stateKey), stateColor)));
        return out;
    }

    /** Локализованная длительность «2 д 5 ч / 6 ч 32 мин». */
    static String duration(String locale, long totalSeconds) {
        long safe = Math.max(0, totalSeconds);
        long days = safe / 86400;
        long hours = (safe % 86400) / 3600;
        long minutes = (safe % 3600) / 60;
        long seconds = safe % 60;
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        if (days > 0) {
            sb.append(MessageService.text(locale, "ui.duration.days", days));
            any = true;
        }
        if (hours > 0) {
            if (any) {
                sb.append(' ');
            }
            sb.append(MessageService.text(locale, "ui.duration.hours", hours));
            any = true;
        }
        if (minutes > 0) {
            if (any) {
                sb.append(' ');
            }
            sb.append(MessageService.text(locale, "ui.duration.minutes", minutes));
            any = true;
        }
        if (!any) {
            sb.append(MessageService.text(locale, "ui.duration.seconds", seconds));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- weekly

    private static int weekly(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            WeeklyPlayerInfo info = EconomyCore.weeklyFund().playerWeekly(player.getUUID());
            String locale = MessageService.locale(source);
            String shareText = info.projectedShare() > 0
                    ? EconomyCore.formatter().format(info.projectedShare()) : "";
            String lastWeekText = info.lastWeekAccrued() > 0
                    ? EconomyCore.formatter().format(info.lastWeekAccrued()) : "";
            String reasonText = info.eligible() ? null : reasonLineText(locale, info,
                    EconomyCore.settings().weeklyFund);
            boolean trackingEnabled = EconomyCore.settings().activity.enabled;
            source.sendSuccess(() -> weeklyScreen(locale, info, shareText, lastWeekText, reasonText,
                    trackingEnabled), false);
        } catch (Exception e) {
            source.sendFailure(onlyPlayers(source));
        }
        return 1;
    }

    static MutableComponent weeklyScreen(String locale, WeeklyPlayerInfo info,
                                         String shareText, String lastWeekText, String reasonText,
                                         boolean trackingEnabled) {
        MutableComponent out = EconomyComponents.header(
                MessageService.text(locale, "ui.weekly.title"));
        if (!trackingEnabled) {
            // Честный экран при выключенном учёте: за текущую неделю ничего не накапливается,
            // но уже начисленное за прошлую неделю (замороженный план/выплата) сохраняется.
            out.append(Component.literal("\n"));
            out.append(EconomyComponents.warning(
                    MessageService.text(locale, "ui.weekly.trackingOff")));
            if (info.lastWeekAccrued() > 0) {
                out.append(Component.literal("\n"));
                out.append(EconomyComponents.entry(
                        MessageService.text(locale, "ui.weekly.trackingOffLastWeek"),
                        EconomyComponents.money(lastWeekText)));
            }
            return out;
        }
        if (info.eligible()) {
            long untilEnd = Math.max(0, info.weekEndMillis() - System.currentTimeMillis());
            out.append(Component.literal("\n"));
            out.append(EconomyComponents.entry(
                    MessageService.text(locale, "ui.weekly.active"),
                    EconomyComponents.info(duration(locale, info.activeSeconds()))));
            out.append(Component.literal("\n"));
            out.append(EconomyComponents.entry(
                    MessageService.text(locale, "ui.weekly.days"),
                    EconomyComponents.value(Integer.toString(info.activeDays()))));
            out.append(Component.literal("\n"));
            out.append(EconomyComponents.entry(
                    MessageService.text(locale, "ui.weekly.share"),
                    EconomyComponents.money(shareText)));
            out.append(Component.literal("\n"));
            out.append(EconomyComponents.entry(
                    MessageService.text(locale, "ui.weekly.untilEnd"),
                    EconomyComponents.info(duration(locale, untilEnd))));
            if (info.lastWeekAccrued() > 0) {
                out.append(Component.literal("\n"));
                out.append(EconomyComponents.entry(
                        MessageService.text(locale, "ui.weekly.lastWeek"),
                        EconomyComponents.money(lastWeekText)));
                long autoPayoutAt = info.lastWeekAutoPayoutAt();
                if (autoPayoutAt > 0 && autoPayoutAt > System.currentTimeMillis()) {
                    out.append(Component.literal("\n"));
                    out.append(EconomyComponents.entry(
                            MessageService.text(locale, "ui.weekly.payoutSoon"),
                            EconomyComponents.info(duration(locale,
                                    autoPayoutAt - System.currentTimeMillis()))));
                }
            }
        } else {
            out.append(Component.literal("\n"));
            out.append(EconomyComponents.warning(
                    MessageService.text(locale, "ui.weekly.notEligible")));
            if (reasonText != null && !reasonText.isBlank()) {
                out.append(Component.literal("\n"));
                out.append(EconomyComponents.entry(
                        MessageService.text(locale, "ui.weekly.reason"),
                        EconomyComponents.warning(reasonText)));
            }
        }
        return out;
    }

    /** Конкретная причина неучастия: сколько именно не хватает до ближайшего порога. */
    private static String reasonLineText(String locale, WeeklyPlayerInfo info,
                                         EconomySettings.WeeklyFund cfg) {
        return switch (info.reason()) {
            case MIN_ACTIVE_SECONDS -> MessageService.text(locale, "cmd.weekly.reason.minActive",
                    duration(locale, Math.max(0, cfg.minActiveSeconds - info.activeSeconds())));
            case MIN_ACTIVE_DAYS -> MessageService.text(locale,
                    minDaysKey(Math.max(0, cfg.minActiveDays - info.activeDays())),
                    Math.max(0, cfg.minActiveDays - info.activeDays()));
            case NO_POINTS -> {
                if (!cfg.timePointLevels.isEmpty()) {
                    long missing = cfg.timePointLevels.get(0).activeSeconds() - info.activeSeconds();
                    if (missing > 0) {
                        yield MessageService.text(locale, "cmd.weekly.reason.noPoints",
                                duration(locale, missing));
                    }
                }
                yield MessageService.text(locale, "cmd.weekly.reason.noPointsNone");
            }
            case FORECAST_UNAVAILABLE -> MessageService.text(locale, "cmd.weekly.reason.unavailable");
            case WEEKLY_FUND_DISABLED -> MessageService.text(locale, "cmd.weekly.reason.disabled");
            case EXCLUDED -> MessageService.text(locale, "cmd.weekly.reason.excluded");
            case ACCOUNT_FROZEN -> MessageService.text(locale, "cmd.weekly.reason.frozen");
            case MIN_ACCOUNT_AGE -> MessageService.text(locale, "cmd.weekly.reason.minAge");
        };
    }

    /** Ключ множественного числа «дней» (русская плюрализация; en использует одну форму). */
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

    // ---------------------------------------------------------------- history

    private static int history(CommandContext<CommandSourceStack> context, int page) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            UUID uuid = player.getUUID();
            long total = EconomyCore.ledger().countForPlayer(uuid);
            int totalPages = Math.max(1, (int) ((total + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE));
            int currentPage = Math.max(1, Math.min(page, totalPages));
            List<TransactionRow> rows = EconomyCore.ledger().history(uuid, currentPage, HISTORY_PAGE_SIZE);
            String locale = MessageService.locale(source);

            if (rows.isEmpty()) {
                source.sendSuccess(() -> historyEmpty(locale), false);
                return 1;
            }

            MutableComponent screen = EconomyComponents.headerWith(
                            MessageService.text(locale, "ui.history.title"),
                            EconomyComponents.info(currentPage + "/" + totalPages));
            for (TransactionRow row : rows) {
                screen.append(Component.literal("\n"));
                screen.append(historyLine(locale, uuid, row,
                        EconomyCore.formatter().format(row.amountMinor())));
            }
            screen.append(Component.literal("\n"));
            screen.append(EconomyComponents.navigation(locale, HISTORY_COMMAND,
                    HISTORY_BACK, HISTORY_NEXT, currentPage, totalPages));
            source.sendSuccess(() -> screen, false);
        } catch (Exception e) {
            source.sendFailure(onlyPlayers(source));
        }
        return 1;
    }

    static MutableComponent historyEmpty(String locale) {
        return EconomyComponents.header(
                MessageService.text(locale, "ui.history.title"))
                .append(Component.literal("\n"))
                .append(EconomyComponents.muted(MessageService.text(locale, "ui.history.empty")));
    }

    static MutableComponent historyLine(String locale, UUID viewerUuid,
                                        TransactionRow row, String amountText) {
        boolean returnedOwnMoney = row.targetUuid() != null && row.targetUuid().equals(viewerUuid)
                && (row.type() == TransactionType.ESCROW_RELEASE
                || "buyer-refund".equalsIgnoreCase(row.metadata().get("role")));
        boolean income = row.targetUuid() != null && row.targetUuid().equals(viewerUuid)
                && (returnedOwnMoney || row.sourceUuid() == null || !row.sourceUuid().equals(viewerUuid));
        MutableComponent amount = income
                ? EconomyComponents.income(EconomyComponents.padAmount(amountText, 7))
                : EconomyComponents.expense(EconomyComponents.padAmount(amountText, 7));
        MutableComponent line = amount;
        line.append(EconomyComponents.muted("   "));
        line.append(EconomyComponents.text(historyDescription(locale, viewerUuid, row)));
        MutableComponent hover = EconomyComponents.hover(List.of(
                EconomyComponents.muted(MessageService.text(locale, "ui.history.hover.date",
                        formatHistoryTime(row.createdAt())))));
        line.withStyle(style -> style.withHoverEvent(
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
        return line;
    }

    /** Время операции в зоне недельного фонда (вне сервера/тестов — UTC). */
    private static String formatHistoryTime(long millis) {
        String zone = EconomyCore.isStarted() && EconomyCore.settings() != null
                ? EconomyCore.settings().weeklyFund.timeZone : "UTC";
        return TIME.withZone(ZoneId.of(zone)).format(Instant.ofEpochMilli(millis));
    }

    static String historyDescription(String locale, UUID viewerUuid, TransactionRow row) {
        String reason = safeReason(row.reason());
        if (reason != null) return reason;
        String role = row.metadata().getOrDefault("role", "");
        return switch (row.type()) {
            case ESCROW_RESERVE -> MessageService.text(locale, "ui.history.operation.auctionReserve");
            case ESCROW_RELEASE -> MessageService.text(locale, "ui.history.operation.auctionReturn");
            case ESCROW_CAPTURE -> {
                if ("buyer-refund".equalsIgnoreCase(role)) {
                    yield MessageService.text(locale, "ui.history.operation.auctionDifference");
                }
                boolean seller = row.targetUuid() != null && row.targetUuid().equals(viewerUuid);
                yield MessageService.text(locale, seller
                        ? "ui.history.operation.auctionSale" : "ui.history.operation.auctionPurchase");
            }
            case ESCROW_ROLLOVER -> MessageService.text(locale, "ui.history.operation.auctionPending");
            default -> MessageService.text(locale, "type." + row.type().name());
        };
    }

    /** Причина транзакции может содержать технический префикс — игроку он не нужен. */
    private static String safeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        String lower = trimmed.toLowerCase();
        // Old plugin builds stored implementation details and escrow/order UUIDs as
        // the public reason. Keep those rows useful by falling back to the localized
        // transaction type instead of leaking protocol vocabulary into /money history.
        if (lower.startsWith("buy hold ") || lower.startsWith("settle+rollover ")
                || lower.startsWith("sell settlement ") || lower.startsWith("buy settlement ")
                || lower.startsWith("cancel buy ") || lower.startsWith("expire buy ")
                || lower.matches(".*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*")) {
            return null;
        }
        if (lower.startsWith("pay:") || lower.startsWith("milestone:")
                || lower.startsWith("ftbquests:") || lower.startsWith("admin:")
                || lower.startsWith("compensation:") || lower.startsWith("questcomp:")
                || lower.startsWith("external:") || lower.startsWith("escrow:")
                || lower.startsWith("kubejs:") || lower.startsWith("cmd.type:")
                || lower.startsWith("legacy")) {
            return null;
        }
        return trimmed;
    }

    private static Component onlyPlayers(CommandSourceStack source) {
        return EconomyComponents.error(MessageService.text(source, "cmd.only.players"));
    }
}
