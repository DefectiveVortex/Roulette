package com.vortex.roulette.table;

import com.vortex.roulette.game.TableRules;
import com.vortex.roulette.model.WheelType;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableStoreTest {

    private static final Logger LOG = Logger.getLogger("TableStoreTest");
    private static final TableRules DEFAULTS = TableRules.defaults();
    private static final TableRules HIGH_ROLLER = new TableRules(WheelType.AMERICAN, 100, 5000, 100000, 400, 200, 60);

    static {
        LOG.setUseParentHandlers(false);
    }

    @TempDir
    Path dir;

    private TableStore store() {
        return new TableStore(new File(dir.toFile(), "tables.yml"), LOG);
    }

    @Test
    void aTableSurvivesARestart() {
        TableStore first = store();
        assertTrue(first.load(DEFAULTS).isEmpty());
        TableDef lobby = new TableDef("lobby", "world", 10, 64, -20, BlockFace.EAST, DEFAULTS);
        TableDef vip = new TableDef("vip", "world_nether", -5, 32, 7, BlockFace.NORTH, HIGH_ROLLER);
        assertTrue(first.put(lobby));
        assertTrue(first.put(vip));

        assertEquals(List.of(lobby, vip), store().load(DEFAULTS));
    }

    @Test
    void removingAndReplacing() {
        TableStore store = store();
        store.load(DEFAULTS);
        TableDef lobby = new TableDef("lobby", "world", 10, 64, -20, BlockFace.EAST, DEFAULTS);
        store.put(lobby);
        store.put(lobby.withRules(HIGH_ROLLER));
        assertEquals(List.of(lobby.withRules(HIGH_ROLLER)), store().load(DEFAULTS));
        assertTrue(store.hasEntry("lobby"));
        assertTrue(store.remove("lobby"));
        assertFalse(store.hasEntry("lobby"));
        assertTrue(store().load(DEFAULTS).isEmpty());
    }

    @Test
    void anEntryThatMakesNoSenseIsSkippedButStaysInTheFile() throws IOException {
        Path file = dir.resolve("tables.yml");
        Files.writeString(file, """
                tables:
                  good:
                    world: world
                    x: 1
                    y: 64
                    z: 2
                    facing: south
                  sideways:
                    world: world
                    x: 1
                    y: 64
                    z: 30
                    facing: up
                  greedy:
                    world: world
                    x: 1
                    y: 64
                    z: 60
                    facing: west
                    min-bet: 500
                    max-bet: 100
                  scalar: 5
                """, StandardCharsets.UTF_8);
        TableStore store = store();
        List<TableDef> tables = store.load(DEFAULTS);
        // settings an entry leaves out come from the defaults
        assertEquals(List.of(new TableDef("good", "world", 1, 64, 2, BlockFace.SOUTH, DEFAULTS)), tables);
        // the names stay taken, and a later write keeps the entries for the admin to fix
        assertTrue(store.hasEntry("sideways"));
        assertTrue(store.put(new TableDef("new", "world", 100, 64, 100, BlockFace.EAST, DEFAULTS)));
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.contains("sideways"), written);
        assertTrue(written.contains("greedy"), written);
        assertTrue(written.contains("scalar"), written);
        assertEquals(2, store().load(DEFAULTS).size());
    }

    @Test
    void aFileThatIsNotYamlIsKeptAsideAndWhatParsesIsRescued() throws IOException {
        Path file = dir.resolve("tables.yml");
        Files.writeString(file, """
                tables:
                  lobby:
                    world: world
                    x: 10
                    y: 64
                    z: -20
                    facing: east
                  broken: [unclosed
                """, StandardCharsets.UTF_8);
        List<TableDef> tables = store().load(DEFAULTS);
        try (var listing = Files.list(dir)) {
            assertTrue(listing.anyMatch(p -> p.getFileName().toString().startsWith("tables.yml.broken-")),
                    "the unreadable file must be kept");
        }
        assertEquals(List.of(new TableDef("lobby", "world", 10, 64, -20, BlockFace.EAST, DEFAULTS)), tables);
        assertEquals(tables, store().load(DEFAULTS));
    }

    @Test
    void namesAndFacingsAreChecked() {
        assertTrue(TableDef.isValidId("high_roller-2"));
        assertFalse(TableDef.isValidId(""));
        assertFalse(TableDef.isValidId("two words"));
        assertFalse(TableDef.isValidId("a.b"));
        assertFalse(TableDef.isValidId("x".repeat(33)));
        assertThrows(IllegalArgumentException.class,
                () -> new TableDef("t", "world", 0, 0, 0, BlockFace.UP, DEFAULTS));
        assertThrows(IllegalArgumentException.class,
                () -> new TableDef("t", "world", 0, 0, 0, BlockFace.NORTH_EAST, DEFAULTS));
        TableDef def = new TableDef("t", "world", 5, 70, 9, BlockFace.WEST, DEFAULTS);
        assertEquals(new TableShape.Placement(5, 70, 9, -1, 0), def.placement());
    }
}
