package com.valorcraft.veconomy.audit;

/**
 * Жизненный цикл подозрительного события (широковещательного сигнала или события).
 * Каждое событие появляется как {@link #OPEN} и обрабатывается администратором:
 * подтверждённая проблема — {@link #RESOLVED}, ложное срабатывание — {@link #DISMISSED}.
 */
public enum ResolutionStatus {

    OPEN,
    RESOLVED,
    DISMISSED
}