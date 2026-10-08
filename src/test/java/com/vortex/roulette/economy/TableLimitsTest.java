package com.vortex.roulette.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableLimitsTest {
    private final List<String> warnings = new ArrayList<>();

    private TableLimits of(long min, long max, long payout) {
        return TableLimits.of(min, max, payout, warnings::add);
    }

    @Test
    void sensibleValuesPassUntouched() {
        assertEquals(new TableLimits(10, 500, 20_000), of(10, 500, 20_000));
        assertEquals(new TableLimits(1, 1000, 0), of(1, 1000, 0));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void repairsWhatMakesNoSense() {
        assertEquals(new TableLimits(1, 1, 0), of(0, -5, -1));
        assertEquals(3, warnings.size());
        assertEquals(new TableLimits(50, 50, 0), of(50, 10, 0));
        assertEquals(TableLimits.HIGHEST_BET, of(1, Long.MAX_VALUE, 0).maxBet());
        assertEquals(TableLimits.HIGHEST_BET, of(Long.MAX_VALUE, Long.MAX_VALUE, 0).minBet());
    }

    @Test
    void aPayoutCapNobodyCouldBetUnderIsRaised() {
        assertEquals(350, of(10, 100, 5).maxPayout());
        assertEquals(1, warnings.size());
    }

    @Test
    void aPayoutCapThatRulesOutInsideBetsIsKeptWithAWarning() {
        assertEquals(100, of(10, 100, 100).maxPayout());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("350"), warnings.get(0));
    }

    @Test
    void highestSingleWin() {
        assertEquals(35_000, new TableLimits(1, 1000, 0).highestSingleWin());
        assertEquals(5_000, new TableLimits(1, 1000, 5_000).highestSingleWin());
    }
}
