package com.valorcraft.veconomy.audit;

/**
 * Типы аудит-событий. Сигналы подозрительной активности пишутся в ту же таблицу
 * с северити {@link AuditSeverity#SUSPICIOUS}.
 */
public final class AuditEventType {

    private AuditEventType() {}

    public static final String ACCOUNT_FROZEN = "ACCOUNT_FROZEN";
    public static final String ACCOUNT_UNFROZEN = "ACCOUNT_UNFROZEN";
    public static final String EXCLUSION_CHANGED = "EXCLUSION_CHANGED";
    public static final String MILESTONE_GRANTED = "MILESTONE_GRANTED";
    public static final String MILESTONE_REVOKED = "MILESTONE_REVOKED";
    public static final String ADMIN_BALANCE_CHANGE = "ADMIN_BALANCE_CHANGE";
    public static final String WEEKLY_PAYOUT = "WEEKLY_PAYOUT";

    public static final String SIGNAL_TRANSFER_SPAM = "SIGNAL_TRANSFER_SPAM";
    public static final String SIGNAL_ROUNDTRIP = "SIGNAL_ROUNDTRIP";
    public static final String SIGNAL_OVERSIZED = "SIGNAL_OVERSIZED";
    public static final String SIGNAL_NEW_ACCOUNT = "SIGNAL_NEW_ACCOUNT";
}