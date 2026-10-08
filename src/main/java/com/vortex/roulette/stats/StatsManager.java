package com.vortex.roulette.stats;

import com.vortex.roulette.game.Round;
import com.vortex.roulette.game.RoundListener;
import com.vortex.roulette.game.RoundResult;
import com.vortex.roulette.model.Pocket;
import com.vortex.roulette.util.SafeYaml;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Lifetime roulette statistics in stats.yml: per player, for the house, and how often each number came up.
 * Everything is loaded at start (the file is small) and written back every few minutes while something changed,
 * and on disable. A damaged stats.yml is moved aside and the readable entries are kept.
 *
 * <p>Add it to every round as a {@link RoundListener}; it counts a round when the result is settled, so a round
 * that was refunded leaves no trace.
 */
public final class StatsManager implements RoundListener {
    /** stats.yml version this build writes. */
    static final int FILE_VERSION = 1;
    private static final String VERSION_KEY = "config-version";

    /** What top lists can be ordered by. */
    public enum Ranking {
        WAGERED, WON, NET, BIGGEST, ROUNDS;

        public long of(PlayerStats stats) {
            return switch (this) {
                case WAGERED -> stats.wagered();
                case WON -> stats.paid();
                case NET -> stats.net();
                case BIGGEST -> stats.biggestWin();
                case ROUNDS -> stats.rounds();
            };
        }

        /** True for the rankings that count money. */
        public boolean isMoney() {
            return this != ROUNDS;
        }

        public static Ranking parse(String text) {
            try {
                return valueOf(text.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** Everything in the file. */
    static final class Data {
        final Map<UUID, PlayerStats> players = new LinkedHashMap<>();
        /** Pocket label ("0", "00", "17") → times it came up, over every table. */
        final Map<String, Long> numbers = new TreeMap<>(Comparator.comparingInt((String l) -> Pocket.parse(l).id()));
        long spins;
        long wagered;
        long paid;
    }

    private final Logger log;
    private final File file;
    private final Function<UUID, String> names;
    private Data data = new Data();
    private Pocket lastNumber;
    private boolean dirty;
    /** Set when stats.yml exists but could not be read or moved aside: never write over it. */
    private boolean readOnly;
    private BukkitTask saveTask;

    /** Loads stats.yml and starts the autosave. */
    public StatsManager(JavaPlugin plugin, int saveMinutes) {
        this(new File(plugin.getDataFolder(), "stats.yml"), plugin.getLogger(), StatsManager::bukkitName);
        long ticks = Math.max(1, Math.min(saveMinutes, 1440)) * 60L * 20L;
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, ticks, ticks);
    }

    StatsManager(File file, Logger log, Function<UUID, String> names) {
        this.file = file;
        this.log = log;
        this.names = names;
        try {
            data = read(file, log);
        } catch (IllegalStateException e) {
            log.severe(e.getMessage());
            readOnly = true;
        }
    }

    private static String bukkitName(UUID player) {
        return Bukkit.getOfflinePlayer(player).getName();
    }

    // ---- recording -----------------------------------------------------------------------------------------------

    @Override
    public void resultSettled(Round round, RoundResult result) {
        record(result);
    }

    void record(RoundResult result) {
        lastNumber = result.pocket();
        data.spins++;
        data.numbers.merge(result.pocket().label(), 1L, Long::sum);
        for (RoundResult.PlayerResult player : result.players()) {
            String name = names.apply(player.player());
            PlayerStats stats = data.players.computeIfAbsent(player.player(), id -> new PlayerStats(name));
            if (name != null) {
                stats.name = name;
            }
            stats.record(player.staked(), player.payout());
            data.wagered += player.staked();
            data.paid += player.payout();
        }
        dirty = true;
    }

    // ---- reading -------------------------------------------------------------------------------------------------

    /** The player's numbers, or null if they never finished a round. */
    public PlayerStats stats(UUID player) {
        return data.players.get(player);
    }

    /** Finds a player who has statistics by their last known name, ignoring case; null if there is none. */
    public UUID findByName(String name) {
        for (Map.Entry<UUID, PlayerStats> entry : data.players.entrySet()) {
            if (entry.getValue().name != null && entry.getValue().name.equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public List<String> knownNames() {
        List<String> out = new ArrayList<>();
        for (PlayerStats stats : data.players.values()) {
            if (stats.name != null) {
                out.add(stats.name);
            }
        }
        return out;
    }

    /** The first {@code limit} players by the ranking, best first; players at zero are left out. */
    public List<PlayerStats> top(Ranking ranking, int limit) {
        return data.players.values().stream()
                .filter(stats -> ranking.of(stats) > 0)
                .sorted(Comparator.comparingLong(ranking::of).reversed().thenComparing(s -> String.valueOf(s.name)))
                .limit(Math.max(0, limit))
                .toList();
    }

    /** Spins that reached a result, over every table. */
    public long spins() {
        return data.spins;
    }

    /** Everything players staked on those spins. */
    public long houseWagered() {
        return data.wagered;
    }

    /** Everything the house paid back. */
    public long housePaid() {
        return data.paid;
    }

    /** The last number that came up on any table since the server started; null before the first spin. */
    public Pocket lastNumber() {
        return lastNumber;
    }

    /** How often the number with this label ("0", "00", "17") came up. */
    public long hits(String label) {
        try {
            return data.numbers.getOrDefault(label, 0L);
        } catch (RuntimeException e) {
            return 0; // not a roulette number (the map orders its keys as pockets)
        }
    }

    /** The number that came up most often (lowest number on a tie); null before the first spin. */
    public String hotNumber() {
        String best = null;
        for (Map.Entry<String, Long> entry : data.numbers.entrySet()) {
            if (best == null || entry.getValue() > data.numbers.get(best)) {
                best = entry.getKey();
            }
        }
        return best;
    }

    // ---- file ----------------------------------------------------------------------------------------------------

    /**
     * Reads stats.yml. A file that is not valid YAML is moved to stats.yml.broken-&lt;timestamp&gt; and every entry
     * that still parses is kept and written back at once, so the next autosave cannot drop anything silently.
     *
     * @throws IllegalStateException if the file is unreadable and could not be moved aside: it must not be overwritten
     */
    static Data read(File file, Logger log) {
        if (!file.exists()) {
            return new Data();
        }
        SafeYaml.LoadResult loaded = SafeYaml.load(file, log, false);
        YamlConfiguration yaml = loaded.config();
        if (loaded.status() == SafeYaml.Status.BROKEN) {
            if (loaded.brokenCopy() == null) {
                throw new IllegalStateException("stats.yml could not be read (" + loaded.error() + ") and could not be"
                        + " moved aside. Fix or remove it and restart; statistics are not saved until then.");
            }
            yaml = SafeYaml.salvage(loaded.brokenCopy());
        }
        int version = yaml.getInt(VERSION_KEY, FILE_VERSION);
        if (version > FILE_VERSION) {
            log.warning("stats.yml has " + VERSION_KEY + " " + version + ", newer than this Roulette build ("
                    + FILE_VERSION + "). Reading what this build understands.");
        }
        Data data = new Data();
        data.spins = count(yaml, "house.spins", log);
        data.wagered = count(yaml, "house.wagered", log);
        data.paid = count(yaml, "house.paid", log);
        ConfigurationSection numbers = yaml.getConfigurationSection("numbers");
        if (numbers != null) {
            for (String label : numbers.getKeys(false)) {
                try {
                    Pocket.parse(label);
                    data.numbers.put(label, count(yaml, "numbers." + label, log));
                } catch (RuntimeException e) {
                    log.warning("stats.yml: skipping numbers." + label + ", which is not a roulette number.");
                }
            }
        }
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    log.warning("stats.yml: skipping players." + key + ", which is not a player id.");
                    continue;
                }
                String path = "players." + key + ".";
                PlayerStats stats = new PlayerStats(yaml.getString(path + "name"));
                stats.rounds = count(yaml, path + "rounds", log);
                stats.wins = Math.min(count(yaml, path + "wins", log), stats.rounds);
                stats.wagered = count(yaml, path + "wagered", log);
                stats.paid = count(yaml, path + "paid", log);
                stats.biggestWin = count(yaml, path + "biggest-win", log);
                data.players.put(id, stats);
            }
        }
        if (loaded.status() == SafeYaml.Status.BROKEN) {
            log.warning("stats.yml could not be read (" + loaded.error() + "). Moved it to "
                    + loaded.brokenCopy().getName() + " and kept " + data.players.size() + " player(s) that were readable.");
            try {
                SafeYaml.saveAtomically(toYaml(data), file);
            } catch (IOException e) {
                log.log(Level.WARNING, "Could not write the recovered stats.yml", e);
            }
        }
        return data;
    }

    private static long count(YamlConfiguration yaml, String path, Logger log) {
        Object value = yaml.get(path);
        if (value == null) {
            return 0;
        }
        if (!(value instanceof Integer || value instanceof Long)) {
            log.warning("stats.yml: " + path + " is not a whole number (" + value + "); using 0.");
            return 0;
        }
        long n = ((Number) value).longValue();
        if (n < 0) {
            log.warning("stats.yml: " + path + " can't be negative (" + n + "); using 0.");
            return 0;
        }
        return n;
    }

    static YamlConfiguration toYaml(Data data) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(VERSION_KEY, FILE_VERSION);
        yaml.set("house.spins", data.spins);
        yaml.set("house.wagered", data.wagered);
        yaml.set("house.paid", data.paid);
        data.numbers.forEach((label, hits) -> yaml.set("numbers." + label, hits));
        data.players.forEach((id, stats) -> {
            String path = "players." + id + ".";
            yaml.set(path + "name", stats.name);
            yaml.set(path + "rounds", stats.rounds);
            yaml.set(path + "wins", stats.wins);
            yaml.set(path + "wagered", stats.wagered);
            yaml.set(path + "paid", stats.paid);
            yaml.set(path + "biggest-win", stats.biggestWin);
        });
        return yaml;
    }

    private void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    /** Writes stats.yml now (through a temporary file, so a crash mid-write cannot truncate it). */
    public void save() {
        if (readOnly) {
            return;
        }
        try {
            SafeYaml.saveAtomically(toYaml(data), file);
            dirty = false;
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not save stats.yml", e);
        }
    }

    /** Stops the autosave and writes what changed. */
    public void shutdown() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        saveIfDirty();
    }
}
