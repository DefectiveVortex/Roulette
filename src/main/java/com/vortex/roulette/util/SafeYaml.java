package com.vortex.roulette.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loading and saving YAML files that belong to the server admin without ever losing them.
 * A file that doesn't parse is moved aside (never deleted), writes go through a temporary file so a crash
 * or power cut can't leave a half-written file, and {@link #salvage} recovers what still parses from a
 * broken file. Shared by config repair (D4) and tables.yml (D3). Needs no running server.
 */
public final class SafeYaml {
    public enum Status { OK, MISSING, BROKEN }

    /**
     * @param config     the parsed file; empty when MISSING or BROKEN
     * @param brokenCopy where a BROKEN file was moved to, holding the original bytes. Null when the file
     *                   couldn't be moved: then the caller must not write over the original.
     * @param error      the parser's message on one line (BROKEN only)
     */
    public record LoadResult(YamlConfiguration config, Status status, File brokenCopy, String error) {
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** "key:" with nothing after it but maybe a comment: a section header. */
    private static final Pattern SECTION_HEADER = Pattern.compile("^\\s*(['\"]?)([^'\"#:]+)\\1\\s*:\\s*(#.*)?$");

    private SafeYaml() {
    }

    /**
     * Read a YAML file. A missing file is logged and comes back empty. A file that doesn't parse is renamed
     * to {@code <name>.broken-<yyyyMMdd-HHmmss>}, the parser's message is logged, and it comes back empty;
     * pass {@link LoadResult#brokenCopy()} to {@link #salvage(File)} to recover what's still readable.
     */
    public static LoadResult load(File file, Logger log) {
        return load(file, log, true);
    }

    /** {@link #load(File, Logger)}; with {@code report} false the caller writes its own log lines. */
    public static LoadResult load(File file, Logger log, boolean report) {
        if (!file.exists()) {
            if (report) {
                log.info(file.getName() + " not found; starting with an empty one.");
            }
            return new LoadResult(new YamlConfiguration(), Status.MISSING, null, null);
        }
        String text;
        try {
            // Lenient like Bukkit's own loader: bytes that aren't UTF-8 become U+FFFD instead of failing the read.
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Unreadable (permissions or a failing card): leave it where it is.
            String error = oneLine(e.toString());
            if (report) {
                log.warning(file.getName() + " could not be read (" + error + "). Leaving it untouched.");
            }
            return new LoadResult(new YamlConfiguration(), Status.BROKEN, null, error);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
            return new LoadResult(yaml, Status.OK, null, null);
        } catch (InvalidConfigurationException e) {
            String error = oneLine(e.getMessage());
            File copy = moveAside(file, log);
            if (!report) {
                // the caller logs
            } else if (copy == null) {
                log.warning(file.getName() + " is not valid YAML (" + error + ") and could not be moved aside.");
            } else {
                log.warning(file.getName() + " is not valid YAML (" + error + "). Moved it to " + copy.getName() + ".");
            }
            return new LoadResult(new YamlConfiguration(), Status.BROKEN, copy, error);
        }
    }

    /** Rename a file to {@code <name>.broken-<timestamp>} (with -1, -2... if taken). Null if that failed. */
    public static File moveAside(File file, Logger log) {
        String base = file.getName() + ".broken-" + LocalDateTime.now().format(STAMP);
        File target = new File(file.getParentFile(), base);
        for (int i = 1; target.exists(); i++) {
            target = new File(file.getParentFile(), base + "-" + i);
        }
        try {
            Files.move(file.toPath(), target.toPath());
            return target;
        } catch (IOException e) {
            log.warning("Could not rename " + file.getName() + " to " + target.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /** Write a config through {@code <name>.tmp} and an atomic rename, so the old file survives any failure. */
    public static void saveAtomically(YamlConfiguration config, File file) throws IOException {
        writeAtomically(file, config.saveToString().getBytes(StandardCharsets.UTF_8));
    }

    /** {@link #saveAtomically} for raw bytes, e.g. a bundled default file copied out as-is. */
    public static void writeAtomically(File file, byte[] data) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        Path tmp = new File(parent, file.getName() + ".tmp").toPath();
        try {
            try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                out.write(data);
                out.getFD().sync();
            }
            try {
                Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** {@link #salvage(String)} on a file's contents; empty if the file can't be read at all. */
    public static YamlConfiguration salvage(File file) {
        try {
            return salvage(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new YamlConfiguration();
        }
    }

    /**
     * Everything that still parses in broken YAML. Each top-level block is parsed on its own; a block that
     * fails and is a section ("key:" and indented lines) is salvaged the same way one level down, so one bad
     * line only costs the entries around it. Leading tabs, a common hand-editing mistake, become spaces.
     */
    public static YamlConfiguration salvage(String text) {
        YamlConfiguration out = new YamlConfiguration();
        List<String> lines = new ArrayList<>();
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            lines.add(untab(line));
        }
        salvageInto(out, lines);
        return out;
    }

    /** Number of values (not sections) in a config: how much a salvage recovered. */
    public static int countValues(ConfigurationSection section) {
        int n = 0;
        for (String key : section.getKeys(true)) {
            if (!section.isConfigurationSection(key)) {
                n++;
            }
        }
        return n;
    }

    private static void salvageInto(ConfigurationSection target, List<String> lines) {
        YamlConfiguration whole = parse(String.join("\n", lines));
        if (whole != null) {
            copyInto(whole, target);
            return;
        }
        for (List<String> block : blocks(lines)) {
            YamlConfiguration parsed = parse(String.join("\n", block));
            if (parsed != null) {
                copyInto(parsed, target);
                continue;
            }
            Matcher header = SECTION_HEADER.matcher(block.get(0));
            if (header.matches() && block.size() > 1) {
                String key = header.group(2).trim();
                ConfigurationSection child = target.isConfigurationSection(key)
                    ? target.getConfigurationSection(key) : target.createSection(key);
                salvageInto(child, block.subList(1, block.size()));
                if (child.getKeys(false).isEmpty()) {
                    target.set(key, null);
                }
            }
            // Otherwise the block is a single broken entry: it's dropped here and kept in the broken copy.
        }
    }

    /**
     * Split lines into blocks that each start at the shallowest indentation. Comments, blank lines, deeper
     * lines and list items ("- x" may sit level with its key) belong to the block above them.
     */
    private static List<List<String>> blocks(List<String> lines) {
        int min = Integer.MAX_VALUE;
        for (String line : lines) {
            if (isContent(line)) {
                min = Math.min(min, indent(line));
            }
        }
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = null;
        for (String line : lines) {
            boolean starts = isContent(line) && indent(line) == min && !line.trim().startsWith("-");
            if (starts) {
                current = new ArrayList<>();
                blocks.add(current);
            }
            if (current != null) {
                current.add(line);
            }
        }
        for (List<String> block : blocks) {
            int drop = indent(block.get(0));
            block.replaceAll(l -> l.length() >= drop && l.substring(0, drop).isBlank() ? l.substring(drop) : l.stripLeading());
        }
        return blocks;
    }

    private static boolean isContent(String line) {
        String t = line.trim();
        return !t.isEmpty() && !t.startsWith("#");
    }

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String untab(String line) {
        int i = 0;
        StringBuilder lead = new StringBuilder();
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            lead.append(line.charAt(i) == '\t' ? "  " : " ");
            i++;
        }
        return lead + line.substring(i);
    }

    private static YamlConfiguration parse(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
            return yaml;
        } catch (InvalidConfigurationException | RuntimeException e) {
            return null;
        }
    }

    private static void copyInto(ConfigurationSection from, ConfigurationSection to) {
        for (String key : from.getKeys(true)) {
            if (!from.isConfigurationSection(key)) {
                to.set(key, from.get(key));
            } else if (from.getConfigurationSection(key).getKeys(false).isEmpty() && !to.contains(key)) {
                to.createSection(key);
            }
        }
    }

    /** The parser's multi-line message ("while scanning ... in 'reader', line 3, column 5: ...") on one line. */
    static String oneLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        String s = message.replaceAll("\\s+", " ").trim();
        return s.length() > 240 ? s.substring(0, 237) + "..." : s;
    }
}
