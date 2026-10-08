package com.vortex.roulette.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vortex.roulette.game.PlaceResult;
import com.vortex.roulette.model.PocketColor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The bundled config.yml and messages.yml through the updater and the validator, without a server. */
class ConfigFilesTest {
    @TempDir
    Path dir;

    private final List<String> warnings = new ArrayList<>();
    private Logger log;

    @BeforeEach
    void logger() {
        log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
    }

    private static String bundled(String name) {
        try (InputStream in = ConfigFilesTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new AssertionError(e);
        }
        return yaml;
    }

    private File write(String name, String text) throws IOException {
        return Files.writeString(dir.resolve(name), text).toFile();
    }

    private ConfigFileUpdater.Result update(File file, String resource) {
        return ConfigFileUpdater.update(file, bundled(resource), log, ConfigMigrations.forFile(resource),
            ConfigMigrations.optionalKeys(resource));
    }

    @ParameterizedTest
    @ValueSource(strings = {"config.yml", "messages.yml"})
    void aMissingFileIsCreatedAndThenLeftAlone(String name) throws IOException {
        File file = dir.resolve(name).toFile();

        assertTrue(update(file, name).created());
        assertArrayEquals(bundled(name).getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file.toPath()));

        ConfigFileUpdater.Result second = update(file, name);
        assertFalse(second.created() || second.saved());
        assertEquals(0, second.added());
        assertEquals(List.of(), warnings);
        assertEquals(ConfigFileUpdater.CURRENT_VERSION, second.config().getInt(ConfigFileUpdater.VERSION_KEY));
    }

    @Test
    void theBundledConfigPassesItsOwnValidation() {
        YamlConfiguration config = yaml(bundled("config.yml"));
        assertEquals(0, ConfigValidator.validateConfig(config, yaml(bundled("config.yml")), log));
        assertEquals(List.of(), warnings);
    }

    @Test
    void missingKeysAreAddedAndTheAdminsValuesKept() throws IOException {
        File file = write("config.yml", "config-version: 1\ntable:\n  # our high-roller table\n  max-bet: 25000\n");

        ConfigFileUpdater.Result result = update(file, "config.yml");

        assertTrue(result.saved());
        assertTrue(result.added() > 5);
        assertEquals(25000, result.config().getInt("table.max-bet"));
        assertEquals("european", result.config().getString("table.wheel"));
        String text = Files.readString(file.toPath());
        assertTrue(text.contains("# our high-roller table"), text);
        assertTrue(text.contains("spin-seconds: 8"), text);
        assertTrue(Files.exists(dir.resolve("config.yml.pre-update.bak")));
    }

    @Test
    void anUnreadableFileIsMovedAsideAndRebuilt() throws IOException {
        File file = write("config.yml", "table:\n  wheel: american\n\t{{{ not yaml\n");

        ConfigFileUpdater.Result result = update(file, "config.yml");

        assertNotNull(result.brokenCopy());
        assertTrue(result.brokenCopy().exists());
        assertEquals("american", result.config().getString("table.wheel"));
        assertEquals(1000, result.config().getInt("table.max-bet"));
        assertEquals("american", yaml(Files.readString(file.toPath())).getString("table.wheel"));
    }

    @Test
    void valuesThatMakeNoSenseFallBackToTheDefaults() {
        YamlConfiguration config = yaml(bundled("config.yml"));
        config.set("table.wheel", "French");
        config.set("table.min-bet", 0);
        config.set("table.spin-seconds", 500);
        config.set("table.max-payout", "lots");
        config.set("updates.channel", "nightly");
        config.set("stats.save-minutes", 0);
        config.set("wheel.board-numbers", 99);
        config.set("resource-pack.send", "always");
        config.set("resource-pack.url", "ftp://example.org/pack.zip");
        config.set("resource-pack.sha1", "abc123");

        assertEquals(10, ConfigValidator.validateConfig(config, yaml(bundled("config.yml")), log));
        assertEquals(5, config.getInt("stats.save-minutes"));
        assertEquals(10, config.getInt("wheel.board-numbers"));
        assertEquals("table", config.getString("resource-pack.send"));
        assertEquals("", config.getString("resource-pack.url"));
        assertEquals("", config.getString("resource-pack.sha1"));

        assertEquals("european", config.getString("table.wheel"));
        assertEquals(10, config.getInt("table.min-bet"));
        assertEquals(8, config.getInt("table.spin-seconds"));
        assertEquals(50000, config.getInt("table.max-payout"));
        assertEquals("release", config.getString("updates.channel"));
        assertEquals(10, warnings.size(), warnings.toString());
    }

    @Test
    void aMaximumUnderTheMinimumIsRaisedToIt() {
        YamlConfiguration config = yaml(bundled("config.yml"));
        config.set("table.wheel", "AMERICAN");
        config.set("table.min-bet", 5000);

        ConfigValidator.validateConfig(config, yaml(bundled("config.yml")), log);

        assertEquals("american", config.getString("table.wheel"));
        assertEquals(5000, config.getInt("table.max-bet"));
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    void aFileFromANewerVersionIsNotDowngraded() throws IOException {
        File file = write("config.yml", bundled("config.yml").replace("config-version: 1", "config-version: 99"));

        ConfigFileUpdater.Result result = update(file, "config.yml");

        assertEquals(99, result.config().getInt(ConfigFileUpdater.VERSION_KEY));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("newer than this Roulette build")), warnings.toString());
    }

    @Test
    void everyRefusalAndPocketColourHasAMessage() {
        YamlConfiguration messages = yaml(bundled("messages.yml"));
        for (PlaceResult result : PlaceResult.values()) {
            if (result != PlaceResult.OK) {
                String key = "bet-" + result.name().toLowerCase(Locale.ROOT).replace('_', '-');
                assertTrue(messages.isString(key), key);
            }
        }
        for (PocketColor color : PocketColor.values()) {
            assertTrue(messages.isString("number-" + color.name().toLowerCase(Locale.ROOT)));
        }
        assertNull(messages.getString("no-such-key"));
        assertEquals("a 5 b 5", ConfigManager.fill("a %n% b {n}", "n", 5));
    }
}
