package com.valorcraft.veconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

/**
 * Команда-алиас {@code /pay <player> <amount>} — дублирует {@code /money pay}.
 */
public final class PayCommand {

    private PayCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pay")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                context.getSource().getOnlinePlayerNames(), builder))
                        .then(Commands.argument("amount", StringArgumentType.string())
                                .executes(PayCommand::execute))));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        return MoneyCommand.pay(context);
    }
}
