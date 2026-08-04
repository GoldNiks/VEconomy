package com.valorcraft.veconomy.economy;

import java.util.UUID;

/**
 * Системная казна. Представлена системным аккаунтом в таблице {@code accounts}
 * со специальным фиксированным UUID. Остаток недельного фонда после округления
 * сохраняется в казне и учитывается в статистике.
 */
public final class TreasuryService {

    /** UUID системной казны. */
    public static final UUID TREASURY_UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    public static final String TREASURY_NAME = "SYSTEM_TREASURY";

    private TreasuryService() {}
}
