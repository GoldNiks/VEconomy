package com.valorcraft.economy.api;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;

/**
 * События транзакций валюты. Слушайте их через {@code MinecraftForge.EVENT_BUS}.
 * <p>
 * <b>Pre</b> — вызывается ДО списания/зачисления. Отменяемый; сумму можно изменить через
 * {@link Pre#setAmount(double)} (для TRANSFER это сумма перевода до комиссии).
 * <p>
 * <b>Post</b> — вызывается ПОСЛЕ успешной транзакции, содержит игрока, сумму,
 * новый баланс и тип транзакции.
 */
public abstract class EconomyTransactionEvent extends Event {

    public enum Type {
        DEPOSIT,
        WITHDRAW,
        TRANSFER
    }

    private final Player player;
    private final Type type;

    protected EconomyTransactionEvent(Player player, Type type) {
        this.player = player;
        this.type = type;
    }

    /** Игрок, чей баланс затрагивается. */
    public Player getPlayer() {
        return player;
    }

    /** Тип транзакции. */
    public Type getType() {
        return type;
    }

    @Cancelable
    public static class Pre extends EconomyTransactionEvent {

        private double amount;

        public Pre(Player player, Type type, double amount) {
            super(player, type);
            this.amount = amount;
        }

        /** Сумма транзакции (для TRANSFER — сумма перевода, комиссия считается отдельно). */
        public double getAmount() {
            return amount;
        }

        /** Изменить сумму транзакции. */
        public void setAmount(double amount) {
            this.amount = amount;
        }
    }

    public static class Post extends EconomyTransactionEvent {

        private final double amount;
        private final double newBalance;

        public Post(Player player, Type type, double amount, double newBalance) {
            super(player, type);
            this.amount = amount;
            this.newBalance = newBalance;
        }

        /** Фактическая сумма, списанная/зачисленная. */
        public double getAmount() {
            return amount;
        }

        /** Новый баланс игрока после транзакции. */
        public double getNewBalance() {
            return newBalance;
        }
    }
}
