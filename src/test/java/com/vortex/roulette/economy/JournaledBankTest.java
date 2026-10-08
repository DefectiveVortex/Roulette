package com.vortex.roulette.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The bank against an economy in a map: every way a round can end, and every point a server can die at. */
class JournaledBankTest {
    private static final Logger LOG = Logger.getLogger("JournaledBankTest");
    private static final UUID ANN = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @TempDir
    Path dir;
    private final MapWallet wallet = new MapWallet();
    private final List<StakeJournal> opened = new ArrayList<>();
    private JournaledBank bank;
    private UUID round;

    @BeforeEach
    void start() throws IOException {
        wallet.balances.put(ANN, 1000L);
        wallet.balances.put(BOB, 1000L);
        bank = boot();
        round = UUID.randomUUID();
    }

    /** A server start: a new bank on the same journal file and the same economy, recovery included. */
    private JournaledBank boot() throws IOException {
        StakeJournal journal = StakeJournal.open(dir.resolve("stakes.journal"), LOG);
        opened.add(journal);
        JournaledBank started = new JournaledBank(wallet, journal, LOG);
        started.recover();
        started.deliverAll();
        return started;
    }

    private long total() {
        return wallet.balance(ANN) + wallet.balance(BOB);
    }

    @Test
    void aLosingRoundCostsTheStake() {
        assertTrue(bank.reserve(round, ANN, 100));
        assertEquals(900, wallet.balance(ANN));
        bank.pay(round, ANN, 100, 0);
        assertEquals(900, wallet.balance(ANN));
        assertEquals(0, bank.journal().heldTotal());
    }

    @Test
    void aWinningRoundPaysStakePlusWinnings() {
        bank.reserve(round, ANN, 10);
        bank.pay(round, ANN, 10, 360);
        assertEquals(1350, wallet.balance(ANN));
    }

    @Test
    void reserveTakesNothingWhenItFails() {
        assertFalse(bank.reserve(round, ANN, 1001), "more than the balance");
        assertFalse(bank.reserve(round, ANN, 0));
        assertFalse(bank.reserve(round, ANN, -10));
        wallet.refuseWithdrawals = true;
        assertFalse(bank.reserve(round, ANN, 10));
        wallet.refuseWithdrawals = false;
        wallet.available = false;
        assertFalse(bank.reserve(round, ANN, 10));
        wallet.available = true;
        assertEquals(1000, wallet.balance(ANN));
        assertEquals(0, bank.journal().heldTotal());
    }

    @Test
    void takingAChipBackAndLeavingRefund() {
        bank.reserve(round, ANN, 100);
        bank.reserve(round, ANN, 100);
        bank.refund(round, ANN, 100);
        assertEquals(900, wallet.balance(ANN));
        bank.refund(round, ANN, 100);
        assertEquals(1000, wallet.balance(ANN));
    }

    @Test
    void aRefundHappensOnceHoweverOftenItIsAsked() {
        bank.reserve(round, ANN, 100);
        bank.refund(round, ANN, 100);
        bank.refund(round, ANN, 100);
        bank.pay(round, ANN, 100, 3600);
        assertEquals(1000, wallet.balance(ANN));
    }

    @Test
    void aPayoutHappensOnce() {
        bank.reserve(round, ANN, 100);
        bank.pay(round, ANN, 100, 200);
        bank.pay(round, ANN, 100, 200);
        bank.refund(round, ANN, 100);
        assertEquals(1100, wallet.balance(ANN));
    }

    @Test
    void killedDuringBettingRefundsEveryStakeOnce() throws IOException {
        bank.reserve(round, ANN, 100);
        bank.reserve(round, BOB, 250);
        bank.refund(round, BOB, 50);
        assertEquals(1700, total());

        boot();
        assertEquals(1000, wallet.balance(ANN));
        assertEquals(1000, wallet.balance(BOB));

        boot();
        boot();
        assertEquals(2000, total(), "later starts pay nothing more");
    }

    @Test
    void killedHalfwayThroughThePayoutsPaysTheRestTheirStakesBack() throws IOException {
        bank.reserve(round, ANN, 100);
        bank.reserve(round, BOB, 100);
        bank.pay(round, ANN, 100, 200);

        boot();
        assertEquals(1100, wallet.balance(ANN), "paid before the kill, not again after it");
        assertEquals(1000, wallet.balance(BOB), "never settled: the stake comes back");
    }

    @Test
    void aCleanStopLeavesNothingForTheNextStart() throws IOException {
        bank.reserve(round, ANN, 100);
        bank.refund(round, ANN, 100); // Round.abort() on disable
        bank.journal().close();

        boot();
        assertEquals(1000, wallet.balance(ANN));
    }

    @Test
    void aRefusedDepositIsOwedAndArrivesLater() throws IOException {
        List<String> told = new ArrayList<>();
        bank.setOwedListener(new JournaledBank.OwedListener() {
            @Override
            public void owed(UUID player, long amount) {
                told.add("owed " + amount);
            }

            @Override
            public void delivered(UUID player, long amount) {
                told.add("delivered " + amount);
            }
        });
        bank.reserve(round, ANN, 100);
        wallet.refuseDeposits = true;
        bank.pay(round, ANN, 100, 3600);
        assertEquals(900, wallet.balance(ANN));
        assertEquals(3600, bank.journal().owed(ANN));

        assertEquals(0, bank.deliver(ANN), "still refused");
        assertEquals(3600, bank.journal().owed(ANN));

        wallet.refuseDeposits = false;
        assertEquals(3600, bank.deliver(ANN));
        assertEquals(4500, wallet.balance(ANN));
        assertEquals(0, bank.deliver(ANN));
        assertEquals(List.of("owed 3600", "delivered 3600"), told);

        boot();
        assertEquals(4500, wallet.balance(ANN));
    }

    @Test
    void recoveryWaitsForTheEconomy() throws IOException {
        bank.reserve(round, ANN, 100);
        wallet.available = false;
        JournaledBank restarted = boot(); // the economy has not registered yet
        assertEquals(100, restarted.journal().owed(ANN));

        wallet.available = true;
        assertEquals(100, restarted.deliverAll());
        assertEquals(1000, wallet.balance(ANN));
        assertEquals(0, restarted.deliverAll());
    }

    @Test
    void noStakeIsTakenThatCannotBeRecorded() {
        bank.journal().close(); // stands in for a disk that cannot be written
        assertFalse(bank.reserve(round, ANN, 100));
        assertEquals(1000, wallet.balance(ANN));
    }

    @Test
    void moneyIsNeitherMadeNorLostOverManyRounds() throws IOException {
        java.util.Random random = new java.util.Random(42);
        long expected = total();
        for (int i = 0; i < 300; i++) {
            UUID r = UUID.randomUUID();
            for (UUID player : List.of(ANN, BOB)) {
                long stake = 1 + random.nextInt(20);
                if (!bank.reserve(r, player, stake)) {
                    continue;
                }
                switch (random.nextInt(4)) {
                    case 0 -> bank.refund(r, player, stake);
                    case 1 -> {
                        bank.pay(r, player, stake, 0);
                        expected -= stake;
                    }
                    case 2 -> {
                        bank.pay(r, player, stake, stake * 2);
                        expected += stake;
                    }
                    default -> bank = boot(); // killed with the stake on the table
                }
            }
        }
        bank = boot();
        assertEquals(expected, total());
        assertEquals(0, bank.journal().heldTotal());
        assertTrue(bank.journal().owed().isEmpty());
    }
}
