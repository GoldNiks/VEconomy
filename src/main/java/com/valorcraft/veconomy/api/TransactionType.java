package com.valorcraft.veconomy.api;

/**
 * Тип денежной операции. Любое изменение баланса обязано иметь один из этих типов
 * и сопровождаться записью в журнале (ledger).
 */
public enum TransactionType {
    /** Перевод между игроками. */
    PLAYER_TRANSFER,
    /** Начисление за личный этап (milestone). */
    MILESTONE_REWARD,
    /** Еженедельная выплата из недельного фонда. */
    WEEKLY_REWARD,
    /** Административное начисление. */
    ADMIN_DEPOSIT,
    /** Административное списание. */
    ADMIN_WITHDRAW,
    /** Административная корректировка (set): фиксируется предыдущий и новый баланс. */
    ADMIN_SET_ADJUSTMENT,
    /** Импорт данных из устаревшего хранилища. */
    LEGACY_IMPORT,
    /** Резервирование средств эскроу. */
    ESCROW_RESERVE,
    /** Возврат средств из эскроу владельцу. */
    ESCROW_RELEASE,
    /** Передача средств из эскроу получателю. */
    ESCROW_CAPTURE,
    /** Атомарный перенос остатка одного эскроу в следующую reference epoch. */
    ESCROW_ROLLOVER,
    /** Компенсирующая откатная операция. */
    ROLLBACK,
    /** Комиссия. */
    FEE,
    /** Системная корректировка. */
    SYSTEM_CORRECTION,
    /** Награда за квест (FTB Quests). */
    QUEST_REWARD,
    /** Операция через внешний API/скрипт (KubeJS и т.п.). */
    PLUGIN_OPERATION
}
