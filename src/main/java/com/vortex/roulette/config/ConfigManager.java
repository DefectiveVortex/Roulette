package com.vortex.roulette.config;

import com.vortex.roulette.game.PlaceResult;
import com.vortex.roulette.game.TableRules;
import com.vortex.roulette.model.Pocket;
import com.vortex.roulette.model.WheelType;
import java.io.File;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** config.yml and the messages file, checked and repaired by {@link ConfigFileUpdater} on every load. */
public final class ConfigManager {
    private final JavaPlugin plugin;
    private FileConfiguration config;
    private FileConfiguration messages;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * Check, repair and re-read config.yml and the messages file (picks up a changed language too). Runs at
     * startup and on /roulette reload.
     *
     * @return how many warnings the check logged (0 when everything was fine)
     */
    public int reload() {
        CountingLogger log = new CountingLogger(plugin.getLogger());
        String bundled = ConfigFileUpdater.bundledText(plugin, "config.yml");
        YamlConfiguration loaded = ConfigFileUpdater.update(new File(plugin.getDataFolder(), "config.yml"), bundled, log,
            ConfigMigrations.forFile("config.yml"), ConfigMigrations.optionalKeys("config.yml")).config();
        if (bundled != null) {
            ConfigValidator.validateConfig(loaded, parse(bundled), log);
        }
        this.config = loaded;
        this.messages = loadMessages(log);
        return log.problems();
    }

    /**
     * English lives in messages.yml, other languages in messages_<code>.yml. A bundled translation is copied
     * out on first use and repaired and topped up like config.yml; a server can add its own language by dropping
     * in a messages_<code>.yml. Anything a translation lacks falls back to the bundled English.
     */
    private FileConfiguration loadMessages(Logger log) {
        String language = language();
        String fileName = language.isEmpty() || language.equals("en") ? "messages.yml" : "messages_" + language + ".yml";
        File file = new File(plugin.getDataFolder(), fileName);
        String englishText = ConfigFileUpdater.bundledText(plugin, "messages.yml");
        YamlConfiguration english = englishText == null ? new YamlConfiguration() : parse(englishText);

        String bundled = ConfigFileUpdater.bundledText(plugin, fileName);
        if (bundled == null && !file.exists()) {
            log.warning("No messages file for language '" + language + "' (expected " + fileName
                + "). Falling back to English.");
            fileName = "messages.yml";
            file = new File(plugin.getDataFolder(), fileName);
            bundled = englishText;
        }
        YamlConfiguration loaded = ConfigFileUpdater.update(file, bundled, log,
            bundled == null ? List.of() : ConfigMigrations.forFile(fileName), Set.of()).config();
        ConfigValidator.validateTypes(loaded, bundled == null ? english : parse(bundled), fileName, log);
        loaded.setDefaults(english);
        return loaded;
    }

    private static YamlConfiguration parse(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            // the bundled file is broken: ConfigFileUpdater has already logged it
        }
        return yaml;
    }

    // ---- Settings ----

    /** The checked config.yml, for a part reading its own section. */
    public FileConfiguration raw() {
        return config;
    }

    public String language() {
        String language = config.getString("language", "en");
        return language == null ? "en" : language.trim().toLowerCase(Locale.ROOT);
    }

    /** What a new table starts with (config.yml {@code table:}). */
    public TableRules defaultRules() {
        WheelType wheel = "american".equalsIgnoreCase(config.getString("table.wheel"))
            ? WheelType.AMERICAN : WheelType.EUROPEAN;
        long min = Math.max(1, config.getLong("table.min-bet", 10));
        long max = Math.max(min, config.getLong("table.max-bet", 1000));
        long maxPayout = Math.max(0, config.getLong("table.max-payout", 50000));
        return new TableRules(wheel, min, max, maxPayout,
            20 * Math.max(5, config.getInt("table.betting-seconds", 30)),
            20 * Math.max(3, config.getInt("table.spin-seconds", 8)),
            20 * Math.max(1, config.getInt("table.result-seconds", 5)));
    }

    public boolean checkForUpdates() {
        return config.getBoolean("updates.check", true);
    }

    // ---- Messages ----

    /**
     * A message with colour codes applied and placeholders filled in: {@code message("round-won", "amount", 50)}.
     * The prefix is not added; use {@link #prefixed}. An empty string means the admin switched the message off.
     */
    public String message(String key, Object... args) {
        // The messages file carries the bundled English file as its defaults, so a translation
        // that lacks a key still shows the English text instead of an error.
        String message = messages.getString(key);
        if (message == null) {
            message = "&cMissing message: " + key;
        }
        return fill(color(message), args);
    }

    /** {@link #message} with the plugin prefix in front; empty if the message is switched off. */
    public String prefixed(String key, Object... args) {
        String message = message(key, args);
        return message.isEmpty() ? "" : message("prefix") + message;
    }

    public List<String> messageList(String key, Object... args) {
        return messages.getStringList(key).stream().map(line -> fill(color(line), args)).toList();
    }

    public boolean hasMessage(String key) {
        return messages.getString(key) != null;
    }

    /** An amount of money as players see it ("$1,250" in English). */
    public String money(long amount) {
        return message("currency-format", "amount", NumberFormat.getIntegerInstance(Locale.ROOT).format(amount));
    }

    /** A pocket in its colour, e.g. a red "17" or a green "00". */
    public String number(Pocket pocket) {
        return message("number-" + pocket.color().name().toLowerCase(Locale.ROOT), "number", pocket.label());
    }

    /** Why a chip could not be placed or taken back, ready to send. Empty for {@link PlaceResult#OK}. */
    public String refusal(PlaceResult result, TableRules rules) {
        if (result == PlaceResult.OK) {
            return "";
        }
        String key = "bet-" + result.name().toLowerCase(Locale.ROOT).replace('_', '-');
        long max = result == PlaceResult.ABOVE_MAX_PAYOUT ? rules.maxPayout() : rules.maxBet();
        return prefixed(key, "min", money(rules.minBet()), "max", money(max));
    }

    /** Fills %name% (and {name}) placeholders from name, value pairs. */
    public static String fill(String message, Object... args) {
        for (int i = 0; i + 1 < args.length; i += 2) {
            String value = String.valueOf(args[i + 1]);
            message = message.replace("%" + args[i] + "%", value).replace("{" + args[i] + "}", value);
        }
        return message;
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /** Passes everything on and counts the warnings, so a reload can say how many there were. */
    private static final class CountingLogger extends Logger {
        private final Logger target;
        private int problems;

        CountingLogger(Logger target) {
            super(target.getName(), null);
            this.target = target;
            setUseParentHandlers(false);
            setLevel(Level.ALL);
        }

        @Override
        public void log(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                problems++;
            }
            target.log(record);
        }

        int problems() {
            return problems;
        }
    }
}
