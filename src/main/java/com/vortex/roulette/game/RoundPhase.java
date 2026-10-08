package com.vortex.roulette.game;

public enum RoundPhase {
    /** No round running; the first chip opens betting. */
    IDLE,
    /** Countdown running; chips can be placed and taken back. */
    BETTING,
    /** No more bets. The result is drawn and the wheel is being animated towards it. */
    SPINNING,
    /** Paid out; the winning number stays marked until the table clears. */
    RESULT
}
