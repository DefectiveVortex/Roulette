package com.vortex.roulette.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vortex.roulette.game.RoundResult;
import com.vortex.roulette.model.Pocket;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsManagerTest {
    private static final Logger LOG = Logger.getLogger("StatsManagerTest");
    private static final UUID ANN = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Map<UUID, String> NAMES = Map.of(ANN, "Ann", BOB, "Bob");

    @TempDir
    Path dir;

    private File file() {
        return dir.resolve("stats.yml").toFile();
    }

    private StatsManager open() {
        return new StatsManager(file(), LOG, NAMES::get);
    }

    private static RoundResult round(String pocket, long annStaked, long annPayout, long bobStaked, long bobPayout) {
        return new RoundResult(UUID.randomUUID(), Pocket.parse(pocket), List.of(
                new RoundResult.PlayerResult(ANN, annStaked, annPayout, List.of()),
                new RoundResult.PlayerResult(BOB, bobStaked, bobPayout, List.of())));
    }

    @Test
    void countsRoundsMoneyAndNumbers() {
        StatsManager stats = open();
        assertNull(stats.stats(ANN));
        assertNull(stats.lastNumber());
        assertNull(stats.hotNumber());

        stats.record(round("17", 10, 360, 50, 0));
        stats.record(round("00", 20, 0, 50, 100));
        stats.record(round("17", 30, 30, 50, 0));

        PlayerStats ann = stats.stats(ANN);
        assertEquals("Ann", ann.name());
        assertEquals(3, ann.rounds());
        assertEquals(1, ann.wins(), "getting exactly the stake back is not a win");
        assertEquals(60, ann.wagered());
        assertEquals(390, ann.paid());
        assertEquals(330, ann.net());
        assertEquals(350, ann.biggestWin());
        assertEquals(33, ann.winRatePercent());
        assertEquals(-50, stats.stats(BOB).net());

        assertEquals(3, stats.spins());
        assertEquals(210, stats.houseWagered());
        assertEquals(490, stats.housePaid());
        assertEquals("17", stats.lastNumber().label());
        assertEquals("17", stats.hotNumber());
        assertEquals(2, stats.hits("17"));
        assertEquals(1, stats.hits("00"));
        assertEquals(0, stats.hits("5"));
    }

    @Test
    void topListsLeaveOutPlayersAtZeroOrBelow() {
        StatsManager stats = open();
        stats.record(round("17", 10, 360, 50, 0));
        assertEquals(List.of("Ann"), stats.top(StatsManager.Ranking.NET, 10).stream().map(PlayerStats::name).toList());
        assertEquals(List.of("Bob", "Ann"),
                stats.top(StatsManager.Ranking.WAGERED, 10).stream().map(PlayerStats::name).toList());
        assertEquals(1, stats.top(StatsManager.Ranking.ROUNDS, 1).size());
        assertEquals(ANN, stats.findByName("ann"));
        assertNull(stats.findByName("Carol"));
        assertNull(StatsManager.Ranking.parse("nonsense"));
        assertEquals(StatsManager.Ranking.BIGGEST, StatsManager.Ranking.parse("biggest"));
    }

    @Test
    void survivesARestart() {
        StatsManager stats = open();
        stats.record(round("0", 10, 360, 50, 0));
        stats.record(round("00", 10, 0, 50, 0));
        stats.save();

        StatsManager again = open();
        assertEquals(2, again.spins());
        assertEquals(1, again.hits("0"));
        assertEquals(1, again.hits("00"));
        assertEquals(350, again.stats(ANN).biggestWin());
        assertEquals("Bob", again.stats(BOB).name());
        assertEquals(100, again.stats(BOB).wagered());
        assertNull(again.lastNumber(), "the last number is of this server run");
    }

    @Test
    void aDamagedFileIsMovedAsideAndTheReadableEntriesKept() throws IOException {
        StatsManager stats = open();
        stats.record(round("17", 10, 360, 50, 0));
        stats.save();
        Files.writeString(file().toPath(), Files.readString(file().toPath()) + "\n  : [ this is not yaml\n");

        StatsManager again = open();
        assertEquals(360, again.stats(ANN).paid());
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("stats.yml.broken-")));
        }
        again.record(round("3", 10, 0, 0, 0));
        again.save();
        assertEquals(2, open().stats(ANN).rounds());
    }

    @Test
    void badNumbersInTheFileCountAsZero() throws IOException {
        Files.writeString(file().toPath(), String.join("\n",
                "config-version: 1",
                "house: {spins: -4, wagered: lots, paid: 12}",
                "numbers: {'17': 3, '99': 1}",
                "players:",
                "  not-a-uuid: {rounds: 5}",
                "  " + ANN + ": {name: Ann, rounds: 2, wins: 7, wagered: 20, paid: 1.5}",
                ""));
        StatsManager stats = open();
        assertEquals(0, stats.spins());
        assertEquals(0, stats.houseWagered());
        assertEquals(12, stats.housePaid());
        assertEquals(3, stats.hits("17"));
        assertEquals(0, stats.hits("99"));
        assertEquals(2, stats.stats(ANN).wins(), "never more wins than rounds");
        assertEquals(0, stats.stats(ANN).paid());
    }
}
