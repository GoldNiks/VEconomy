package com.valorcraft.veconomy.activity;

/**
 * Строка таблицы {@code weekly_fund_plans}: замороженный план выплаты недели.
 * План фиксируется при первом закрытии недели и не меняется от конфига, денежной массы
 * или последующей активности; повторные попытки выплаты используют сохранённые суммы.
 *
 * @param weekId                    неделя
 * @param fundAmount                итоговый размер фонда (минимальные единицы)
 * @param baseFundAmount            базовый фонд до коэффициента (base × eligible)
 * @param economyCoefficientBps     коэффициент экономики (БП, 10000 = 100%)
 * @param moneySupply               денежная масса на момент закрытия (личные балансы + казна/escrow)
 * @param supplyPerEligible         денежная масса на одного подходящего игрока
 * @param targetSupplyPerEligible   целевая масса на одного подходящего игрока
 * @param eligiblePlayers           число подходящих игроков
 * @param totalPoints               сумма очков подходящих игроков
 * @param totalShare                сумма долей игроков (≤ fundAmount)
 * @param remainderAmount           остаток от деления, уходит в казну
 * @param payoutStatus              PLANNED (не выплачен) или PAID
 * @param plannedAt                 время фиксации плана (мс)
 * @param autoPayoutAt              время автоматической выплаты (мс); null — только вручную
 * @param paidAt                    время завершения выплаты (мс); null — не завершена
 */
public record WeeklyFundPlanRow(
        String weekId,
        long fundAmount,
        long baseFundAmount,
        long economyCoefficientBps,
        long moneySupply,
        long supplyPerEligible,
        long targetSupplyPerEligible,
        int eligiblePlayers,
        long totalPoints,
        long totalShare,
        long remainderAmount,
        String payoutStatus,
        long plannedAt,
        Long autoPayoutAt,
        Long paidAt) {

    /** Статусы плана. */
    public static final String STATUS_PLANNED = "PLANNED";
    public static final String STATUS_PAID = "PAID";

    public boolean paid() {
        return STATUS_PAID.equals(payoutStatus);
    }
}
