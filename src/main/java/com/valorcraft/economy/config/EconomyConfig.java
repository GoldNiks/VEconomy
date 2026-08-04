package com.valorcraft.economy.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** Конфиг economy-core.toml (config/economy-core.toml). */
public final class EconomyConfig {

    public static final ForgeConfigSpec SPEC;

    // --- general ---
    public static final ForgeConfigSpec.DoubleValue STARTING_BALANCE;
    public static final ForgeConfigSpec.ConfigValue<String> CURRENCY_SYMBOL;
    public static final ForgeConfigSpec.IntValue DECIMAL_PLACES;
    public static final ForgeConfigSpec.BooleanValue ALLOW_NEGATIVE_BALANCE;
    public static final ForgeConfigSpec.DoubleValue TRANSFER_TAX;
    public static final ForgeConfigSpec.BooleanValue DEATH_RESET;
    public static final ForgeConfigSpec.BooleanValue LOG_TRANSACTIONS;

    // --- messages ---
    public static final ForgeConfigSpec.ConfigValue<String> MESSAGE_BALANCE_SELF;
    public static final ForgeConfigSpec.ConfigValue<String> MESSAGE_PAYMENT_SENT;
    public static final ForgeConfigSpec.ConfigValue<String> MESSAGE_PAYMENT_RECEIVED;
    public static final ForgeConfigSpec.ConfigValue<String> MESSAGE_INSUFFICIENT_FUNDS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Основные настройки ядра валюты").push("general");

        STARTING_BALANCE = builder
                .comment("Стартовый баланс новых игроков")
                .defineInRange("startingBalance", 100.0, 0.0, 1.0E15);
        CURRENCY_SYMBOL = builder
                .comment("Символ валюты в сообщениях")
                .define("currencySymbol", "⛃");
        DECIMAL_PLACES = builder
                .comment("Формат: 2 = ##.##, 1 = ###.#, 0 = целые")
                .defineInRange("decimalPlaces", 2, 0, 10);
        ALLOW_NEGATIVE_BALANCE = builder
                .comment("Разрешён ли уход в минус")
                .define("allowNegativeBalance", false);
        TRANSFER_TAX = builder
                .comment("Комиссия за перевод в процентах (0 = без комиссии)")
                .defineInRange("transferTax", 0.0, 0.0, 100.0);
        DEATH_RESET = builder
                .comment("Сбрасывать ли баланс при смерти игрока")
                .define("deathReset", false);
        LOG_TRANSACTIONS = builder
                .comment("Писать ли все транзакции в лог-файл logs/economy_transactions.log")
                .define("logTransactions", true);

        builder.pop();

        builder.comment("Кастомные сообщения (поддержка цветовых кодов §)").push("messages");

        MESSAGE_BALANCE_SELF = builder
                .comment("%s - сумма")
                .define("balanceSelf", "§aВаш баланс: §6%s");
        MESSAGE_PAYMENT_SENT = builder
                .comment("Первый %s - сумма, второй %s - игрок")
                .define("paymentSent", "§aВы отправили §6%s §aигроку §e%s");
        MESSAGE_PAYMENT_RECEIVED = builder
                .comment("Первый %s - сумма, второй %s - игрок")
                .define("paymentReceived", "§aВы получили §6%s §aот §e%s");
        MESSAGE_INSUFFICIENT_FUNDS = builder
                .define("insufficientFunds", "§cНедостаточно средств!");

        builder.pop();

        SPEC = builder.build();
    }

    private EconomyConfig() {}
}
