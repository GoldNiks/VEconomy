package com.valorcraft.economy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.valorcraft.economy.api.EconomyAPI;
import com.valorcraft.economy.api.TransactionResult;
import com.valorcraft.economy.config.EconomyConfig;
import com.valorcraft.economy.storage.BalanceStorage;
import com.valorcraft.economy.util.MessageHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Команды: /economy (алиас /eco).
 * balance [игрок] — право 0 (чужой баланс — право 2)
 * pay &lt;игрок&gt; &lt;сумма&gt; — право 0
 * set &lt;игрок&gt; &lt;сумма&gt; — право 4
 * add &lt;игрок&gt; &lt;сумма&gt; — право 3
 * top [страница] — право 0
 */
public final class EconomyCommand {

    private static final int TOP_PAGE_SIZE = 10;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var economy = Commands.literal("economy")
                .then(Commands.literal("balance")
                        .executes(EconomyCommand::balanceSelf)
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(source -> source.hasPermission(2))
                                .executes(EconomyCommand::balanceOther)))
                .then(Commands.literal("pay")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                        .executes(EconomyCommand::pay))))
                .then(Commands.literal("set")
                        .requires(source -> source.hasPermission(4))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg())
                                        .executes(EconomyCommand::set))))
                .then(Commands.literal("add")
                        .requires(source -> source.hasPermission(3))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg())
                                        .executes(EconomyCommand::add))))
                .then(Commands.literal("top")
                        .executes(context -> top(context, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> top(context, IntegerArgumentType.getInteger(context, "page")))));

        dispatcher.register(economy);
        dispatcher.register(Commands.literal("eco").redirect(economy.build()));
    }

    private static int balanceSelf(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            String message = String.format(EconomyConfig.MESSAGE_BALANCE_SELF.get(),
                    EconomyAPI.format(EconomyAPI.getBalance(player)));
            source.sendSuccess(() -> MessageHelper.parse(message), false);
        } catch (Exception e) {
            source.sendFailure(MessageHelper.parse("§cКоманда доступна только игрокам."));
        }
        return 1;
    }

    private static int balanceOther(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer target = EntityArgument.getPlayer(context, "player");
            double balance = EconomyAPI.getBalance(target);
            source.sendSuccess(() -> MessageHelper.parse(String.format(
                    "§aБаланс игрока §e%s§a: §6%s", target.getGameProfile().getName(), EconomyAPI.format(balance))), false);
        } catch (Exception e) {
            source.sendFailure(MessageHelper.parse("§cИгрок не найден."));
        }
        return 1;
    }

    private static int pay(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer sender = source.getPlayerOrException();
            ServerPlayer target = EntityArgument.getPlayer(context, "player");
            double amount = DoubleArgumentType.getDouble(context, "amount");

            if (sender.getUUID().equals(target.getUUID())) {
                source.sendFailure(MessageHelper.parse("§cНельзя переводить самому себе."));
                return 1;
            }

            TransactionResult result = EconomyAPI.transfer(sender, target, amount);
            switch (result) {
                case SUCCESS -> {
                    source.sendSuccess(() -> MessageHelper.parse(String.format(
                            EconomyConfig.MESSAGE_PAYMENT_SENT.get(),
                            EconomyAPI.format(amount), target.getGameProfile().getName())), false);
                    target.sendSystemMessage(MessageHelper.parse(String.format(
                            EconomyConfig.MESSAGE_PAYMENT_RECEIVED.get(),
                            EconomyAPI.format(amount), sender.getGameProfile().getName())));
                }
                case INSUFFICIENT_FUNDS -> source.sendFailure(MessageHelper.parse(
                        EconomyConfig.MESSAGE_INSUFFICIENT_FUNDS.get()));
                default -> source.sendFailure(MessageHelper.parse("§cНе удалось выполнить перевод."));
            }
        } catch (Exception e) {
            source.sendFailure(MessageHelper.parse("§cИгрок не найден."));
        }
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer target = EntityArgument.getPlayer(context, "player");
            double amount = DoubleArgumentType.getDouble(context, "amount");

            if (!EconomyConfig.ALLOW_NEGATIVE_BALANCE.get() && amount < 0) {
                source.sendFailure(MessageHelper.parse("§cОтрицательный баланс запрещён конфигом (allowNegativeBalance = false)."));
                return 1;
            }

            TransactionResult result = EconomyAPI.forceSet(target, amount);
            if (result == TransactionResult.SUCCESS) {
                source.sendSuccess(() -> MessageHelper.parse(String.format(
                        "§aБаланс игрока §e%s §aустановлен: §6%s",
                        target.getGameProfile().getName(), EconomyAPI.format(amount))), true);
            } else {
                source.sendFailure(MessageHelper.parse("§cНе удалось установить баланс."));
            }
        } catch (Exception e) {
            source.sendFailure(MessageHelper.parse("§cИгрок не найден."));
        }
        return 1;
    }

    private static int add(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer target = EntityArgument.getPlayer(context, "player");
            double amount = DoubleArgumentType.getDouble(context, "amount");

            if (amount == 0) {
                source.sendFailure(MessageHelper.parse("§cСумма не может быть нулевой."));
                return 1;
            }

            TransactionResult result = amount > 0
                    ? EconomyAPI.deposit(target, amount)
                    : EconomyAPI.withdraw(target, -amount);
            if (result == TransactionResult.SUCCESS) {
                source.sendSuccess(() -> MessageHelper.parse(String.format(
                        "§aБаланс игрока §e%s §aизменён на §6%s§a. Новый баланс: §6%s",
                        target.getGameProfile().getName(), EconomyAPI.format(amount),
                        EconomyAPI.format(EconomyAPI.getBalance(target)))), true);
            } else {
                source.sendFailure(MessageHelper.parse("§cОперация не выполнена (недостаточно средств или неверная сумма)."));
            }
        } catch (Exception e) {
            source.sendFailure(MessageHelper.parse("§cИгрок не найден."));
        }
        return 1;
    }

    private static int top(CommandContext<CommandSourceStack> context, int page) {
        CommandSourceStack source = context.getSource();
        List<Map.Entry<UUID, Double>> entries = new ArrayList<>(BalanceStorage.snapshot().entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        int totalPages = Math.max(1, (entries.size() + TOP_PAGE_SIZE - 1) / TOP_PAGE_SIZE);
        int currentPage = Math.max(1, Math.min(page, totalPages));
        int from = (currentPage - 1) * TOP_PAGE_SIZE;

        source.sendSuccess(() -> MessageHelper.parse("§6=========== ТОП БОГАЧЕЙ СЕРВЕРА ==========="), false);
        source.sendSuccess(() -> MessageHelper.parse(String.format("§eСтраница §f%d§e/§f%d", currentPage, totalPages)), false);

        if (entries.isEmpty()) {
            source.sendSuccess(() -> MessageHelper.parse("§7Пока никого."), false);
            return 1;
        }

        for (int i = from; i < Math.min(from + TOP_PAGE_SIZE, entries.size()); i++) {
            Map.Entry<UUID, Double> entry = entries.get(i);
            String name = playerName(source, entry.getKey());
            int rank = i + 1;
            source.sendSuccess(() -> MessageHelper.parse(String.format("§e%d. §f%s §7— §6%s",
                    rank, name, EconomyAPI.format(entry.getValue()))), false);
        }
        return 1;
    }

    private static String playerName(CommandSourceStack source, UUID uuid) {
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        try {
            var profile = source.getServer().getProfileCache().get(uuid);
            if (profile.isPresent()) {
                return profile.get().getName();
            }
        } catch (Exception ignored) {
        }
        return "§7(оффлайн)";
    }

    private EconomyCommand() {}
}
