package com.vortex.roulette.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StakeJournalTest {
    private static final Logger LOG = Logger.getLogger("StakeJournalTest");
    private static final UUID ROUND = UUID.randomUUID();
    private static final UUID ANN = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("stakes.journal");
    }

    private StakeJournal open() throws IOException {
        return StakeJournal.open(file(), LOG);
    }

    /** What a new process would see: the file as it is on disk right now, without closing the writer. */
    private StakeJournal reopenAsAfterKill() throws IOException {
        return open();
    }

    @Test
    void holdsWhatWasReservedUntilRefundedOrPaid() throws IOException {
        try (StakeJournal j = open()) {
            j.reserve(ROUND, ANN, 100);
            j.reserve(ROUND, ANN, 50);
            j.reserve(ROUND, BOB, 30);
            assertEquals(150, j.held(ROUND, ANN));
            assertEquals(180, j.heldTotal());

            assertEquals(50, j.refund(ROUND, ANN, 50));
            assertEquals(100, j.held(ROUND, ANN));
            assertEquals(100, j.pay(ROUND, ANN, 3600));
            assertEquals(0, j.held(ROUND, ANN));
            assertEquals(30, j.pay(ROUND, BOB, 0));
            assertEquals(0, j.heldTotal());
        }
    }

    @Test
    void neverReleasesMoreThanIsHeld() throws IOException {
        try (StakeJournal j = open()) {
            j.reserve(ROUND, ANN, 100);
            assertEquals(100, j.refund(ROUND, ANN, 250));
            assertEquals(0, j.refund(ROUND, ANN, 100), "a second refund finds nothing");
            assertEquals(0, j.pay(ROUND, ANN, 3600), "a payout after the refund finds nothing");
            assertEquals(0, j.refund(ROUND, BOB, 10), "a player who never bet");
            assertEquals(0, j.refund(ROUND, ANN, -5));
        }
    }

    @Test
    void rejectsAStakeThatIsNotPositive() throws IOException {
        try (StakeJournal j = open()) {
            assertThrows(IllegalArgumentException.class, () -> j.reserve(ROUND, ANN, 0));
            assertThrows(IllegalArgumentException.class, () -> j.reserve(ROUND, ANN, -1));
            assertEquals(0, j.heldTotal());
        }
    }

    @Test
    void aKilledServerStillHoldsEveryOpenStake() throws IOException {
        StakeJournal before = open();
        before.reserve(ROUND, ANN, 100);
        before.reserve(ROUND, BOB, 40);
        before.refund(ROUND, BOB, 15);
        // no close(): the process is gone

        try (StakeJournal after = reopenAsAfterKill()) {
            assertEquals(100, after.held(ROUND, ANN));
            assertEquals(25, after.held(ROUND, BOB));
            assertEquals(125, after.voidHeld());
            assertEquals(0, after.heldTotal());
            assertEquals(Map.of(ANN, 100L, BOB, 25L), after.owed());
        }
        before.close();
    }

    @Test
    void recoveryRunsOnce() throws IOException {
        StakeJournal first = open();
        first.reserve(ROUND, ANN, 100);

        StakeJournal second = reopenAsAfterKill();
        assertEquals(100, second.voidHeld());
        assertEquals(100, second.deliver(ANN));
        // killed again right after the refund went out

        try (StakeJournal third = reopenAsAfterKill()) {
            assertEquals(0, third.heldTotal());
            assertEquals(0, third.voidHeld());
            assertEquals(0, third.owed(ANN));
            assertEquals(0, third.deliver(ANN));
        }
        first.close();
        second.close();
    }

    @Test
    void killedBetweenVoidAndDeliveryStillOwes() throws IOException {
        StakeJournal first = open();
        first.reserve(ROUND, ANN, 100);
        StakeJournal second = reopenAsAfterKill();
        second.voidHeld();

        try (StakeJournal third = reopenAsAfterKill()) {
            assertEquals(0, third.heldTotal());
            assertEquals(100, third.owed(ANN));
        }
        first.close();
        second.close();
    }

    @Test
    void aSettledRoundLeavesNothingBehind() throws IOException {
        StakeJournal first = open();
        first.reserve(ROUND, ANN, 100);
        first.reserve(ROUND, BOB, 100);
        first.pay(ROUND, ANN, 200);
        // killed after Ann was paid and before Bob was

        try (StakeJournal second = reopenAsAfterKill()) {
            assertEquals(0, second.held(ROUND, ANN), "Ann's stake was consumed by the payout");
            assertEquals(100, second.held(ROUND, BOB), "Bob's is still his");
        }
        first.close();
    }

    @Test
    void owedSurvivesRestartsUntilDelivered() throws IOException {
        try (StakeJournal j = open()) {
            j.owe(ANN, 70);
            j.owe(ANN, 30);
            j.owe(BOB, 5);
            j.owe(BOB, 0);
        }
        try (StakeJournal j = open()) {
            assertEquals(100, j.owed(ANN));
            assertEquals(100, j.deliver(ANN));
            assertEquals(0, j.owed(ANN));
            j.owe(ANN, 100); // the deposit was refused again
        }
        try (StakeJournal j = open()) {
            assertEquals(Map.of(ANN, 100L, BOB, 5L), j.owed());
        }
    }

    @Test
    void anUnfinishedLastLineIsDroppedAndDoesNotSwallowTheNextEvent() throws IOException {
        try (StakeJournal j = open()) {
            j.reserve(ROUND, ANN, 100);
        }
        // a power cut in the middle of the next write: the amount is cut from 500 to 5
        Files.writeString(file(), "1 RESERVE " + ROUND + " " + BOB + " 5", StandardOpenOption.APPEND);

        try (StakeJournal j = open()) {
            assertEquals(100, j.held(ROUND, ANN));
            assertEquals(0, j.held(ROUND, BOB), "a cut-off amount is not trusted");
            j.reserve(ROUND, BOB, 40);
        }
        try (StakeJournal j = open()) {
            assertEquals(100, j.held(ROUND, ANN));
            assertEquals(40, j.held(ROUND, BOB));
        }
    }

    @Test
    void garbageLinesAreSkipped() throws IOException {
        Files.writeString(file(), String.join("\n",
                StakeJournal.HEADER,
                "1 RESERVE " + ROUND + " " + ANN + " 100",
                "what is this",
                "2 RESERVE not-a-uuid " + ANN + " 100",
                "3 RESERVE " + ROUND + " " + ANN + " -100",
                "4 REFUND " + ROUND + " " + ANN + " 999999",
                "5 RESERVE " + ROUND + " " + BOB + " 60",
                "6 PAY " + ROUND + " " + BOB,
                "7 OWE " + ANN + " 12",
                "8 DELIVER " + ANN + " 500",
                "9 MYSTERY " + ANN + " 1",
                "") , StandardCharsets.UTF_8);

        try (StakeJournal j = open()) {
            assertEquals(0, j.held(ROUND, ANN), "the oversized refund releases what was held and no more");
            assertEquals(60, j.held(ROUND, BOB), "a PAY line without its payout is torn and ignored");
            assertEquals(0, j.owed(ANN), "delivering more than was owed leaves nothing, not a debt");
        }
    }

    @Test
    void writesOneReadableLinePerEvent() throws IOException {
        try (StakeJournal j = StakeJournal.open(file(), LOG, () -> 1_700_000_000_000L, StakeJournal.DEFAULT_ROTATE_BYTES)) {
            j.reserve(ROUND, ANN, 100);
            j.pay(ROUND, ANN, 3600);
            j.owe(ANN, 3600);
            j.deliver(ANN);
        }
        assertEquals(List.of(
                StakeJournal.HEADER,
                "1700000000000 RESERVE " + ROUND + " " + ANN + " 100",
                "1700000000000 PAY " + ROUND + " " + ANN + " 100 3600",
                "1700000000000 OWE " + ANN + " 3600",
                "1700000000000 DELIVER " + ANN + " 3600"), Files.readAllLines(file()));
    }

    @Test
    void rotatesOnlyWhileNoRoundIsOpenAndKeepsWhatIsOwed() throws IOException {
        try (StakeJournal j = StakeJournal.open(file(), LOG, System::currentTimeMillis, 400)) {
            j.owe(BOB, 7);
            UUID open = UUID.randomUUID();
            j.reserve(open, ANN, 10);
            for (int i = 0; i < 20; i++) {
                UUID round = UUID.randomUUID();
                j.reserve(round, ANN, 10);
                j.pay(round, ANN, 0);
            }
            assertFalse(Files.exists(j.previousFile()), "a stake is still held: the file must stay whole");
            assertTrue(Files.size(file()) > 400);

            j.refund(open, ANN, 10);
            assertTrue(Files.exists(j.previousFile()));
            assertEquals(List.of(StakeJournal.HEADER), Files.readAllLines(file()).subList(0, 1));
            assertEquals(2, Files.readAllLines(file()).size(), "the header and Bob's OWE line");

            j.reserve(ROUND, ANN, 25); // and it keeps working after the rotation
        }
        try (StakeJournal j = open()) {
            assertEquals(7, j.owed(BOB));
            assertEquals(25, j.held(ROUND, ANN));
        }
    }

    @Test
    void anInterruptedRotationIsFinishedOrUndoneAtTheNextStart() throws IOException {
        // Killed after the old journal was moved away and before the snapshot took its place.
        Files.writeString(dir.resolve("stakes.journal.tmp"), StakeJournal.HEADER + "\n1 OWE " + ANN + " 9\n");
        try (StakeJournal j = open()) {
            assertEquals(9, j.owed(ANN));
        }
        // Killed while the snapshot was being written: the journal is still there and is the truth.
        Files.writeString(dir.resolve("stakes.journal.tmp"), StakeJournal.HEADER + "\n1 OWE " + ANN + " 1000\n");
        try (StakeJournal j = open()) {
            assertEquals(9, j.owed(ANN));
            assertFalse(Files.exists(dir.resolve("stakes.journal.tmp")));
        }
    }

    @Test
    void refusesStakesWhileTheFileCannotBeWritten() throws IOException {
        Path blocked = dir.resolve("missing").resolve("stakes.journal");
        StakeJournal j = StakeJournal.open(blocked, LOG);
        j.close();
        assertFalse(j.writable(), "a closed journal takes nothing");
    }
}
