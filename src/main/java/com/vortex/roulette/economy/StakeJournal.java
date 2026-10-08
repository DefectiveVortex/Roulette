package com.vortex.roulette.economy;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The durable record of every stake the plugin holds and every amount it still owes. Append-only text, one line per
 * event; {@code held} and {@code owed} are nothing but a replay of the file. The format, the order of journal line
 * and economy call, and what a crash can and cannot do are in {@code docs/MONEY.md}.
 *
 * <p>Pure Java. One thread writes (the server thread); a daemon thread only calls {@code force}.
 */
public final class StakeJournal implements Closeable {
    static final String HEADER = "# roulette stake journal v1";
    /** Rotate once no round is open and the file is larger than this. */
    static final long DEFAULT_ROTATE_BYTES = 1L << 20;
    private static final long FORCE_INTERVAL_MS = 200;
    private static final long REOPEN_INTERVAL_MS = 5_000;

    private final Path file;
    private final Logger log;
    private final LongSupplier clock;
    private final long rotateBytes;
    /** Round → player → stake still held. */
    private final Map<UUID, Map<UUID, Long>> held = new LinkedHashMap<>();
    private final Map<UUID, Long> owed = new LinkedHashMap<>();

    private volatile FileChannel channel;
    private boolean broken;
    private long lastReopenAttempt;
    private final Object forceLock = new Object();
    private boolean dirty;
    private boolean closed;
    private Thread forcer;

    private StakeJournal(Path file, Logger log, LongSupplier clock, long rotateBytes) {
        this.file = file;
        this.log = log;
        this.clock = clock;
        this.rotateBytes = rotateBytes;
    }

    /** Opens (or creates) the journal and replays it. Stakes of rounds that died with the server are still held. */
    public static StakeJournal open(Path file, Logger log) throws IOException {
        return open(file, log, System::currentTimeMillis, DEFAULT_ROTATE_BYTES);
    }

    static StakeJournal open(Path file, Logger log, LongSupplier clock, long rotateBytes) throws IOException {
        StakeJournal journal = new StakeJournal(file, log, clock, rotateBytes);
        journal.load();
        journal.startForcer();
        return journal;
    }

    // ---- state ---------------------------------------------------------------------------------------------------

    /** What this player still has reserved in this round. */
    public long held(UUID round, UUID player) {
        Map<UUID, Long> players = held.get(round);
        return players == null ? 0 : players.getOrDefault(player, 0L);
    }

    /** Everything held, over all rounds and players. */
    public long heldTotal() {
        long total = 0;
        for (Map<UUID, Long> players : held.values()) {
            for (long amount : players.values()) {
                total += amount;
            }
        }
        return total;
    }

    /** What the plugin owes this player because a deposit was refused or the server died mid-round. */
    public long owed(UUID player) {
        return owed.getOrDefault(player, 0L);
    }

    /** A copy of everything owed, by player. */
    public Map<UUID, Long> owed() {
        return new LinkedHashMap<>(owed);
    }

    /**
     * False while the file cannot be written; no stake may be taken then. Tries to reopen the file, at most once
     * every few seconds.
     */
    public boolean writable() {
        if (broken && !closed && clock.getAsLong() - lastReopenAttempt >= REOPEN_INTERVAL_MS) {
            lastReopenAttempt = clock.getAsLong();
            try {
                closeChannel();
                openChannel();
                broken = false;
                log.info("Roulette stake journal is writable again; tables take bets.");
            } catch (IOException e) {
                // still broken; the first failure was logged
            }
        }
        return !broken && !closed;
    }

    // ---- events --------------------------------------------------------------------------------------------------

    /**
     * Records a stake that has just been withdrawn.
     *
     * @throws IOException if it could not be written: nothing is held, the caller must give the money back
     */
    public void reserve(UUID round, UUID player, long amount) throws IOException {
        requirePositive(amount);
        write(line("RESERVE", round, player, amount));
        addHeld(round, player, amount);
    }

    /**
     * Releases up to {@code amount} of what the player has reserved in the round. Call before depositing.
     *
     * @return what was released and must now be deposited: {@code amount}, or less if less was held (0 = nothing)
     */
    public long refund(UUID round, UUID player, long amount) {
        long released = Math.min(Math.max(amount, 0), held(round, player));
        if (released > 0) {
            append(line("REFUND", round, player, released));
            takeHeld(round, player, released);
            rotateIfIdle();
        }
        return released;
    }

    /**
     * Consumes everything the player has reserved in the round and records the payout. Call before depositing.
     *
     * @return the stake that was consumed; 0 means nothing was held and nothing may be paid
     */
    public long pay(UUID round, UUID player, long payout) {
        long staked = held(round, player);
        if (staked > 0) {
            append(line("PAY", round, player, staked) + " " + Math.max(payout, 0));
            takeHeld(round, player, staked);
            rotateIfIdle();
        }
        return staked;
    }

    /**
     * Recovery: every stake still held belongs to a round that no longer exists and becomes owed to its player.
     *
     * @return the total that was moved
     */
    public long voidHeld() {
        long total = 0;
        StringBuilder lines = new StringBuilder();
        for (Map.Entry<UUID, Map<UUID, Long>> round : held.entrySet()) {
            for (Map.Entry<UUID, Long> stake : round.getValue().entrySet()) {
                lines.append(line("VOID", round.getKey(), stake.getKey(), stake.getValue()));
                owed.merge(stake.getKey(), stake.getValue(), Long::sum);
                total += stake.getValue();
            }
        }
        if (total > 0) {
            append(lines.toString());
            held.clear();
        }
        rotateIfIdle();
        return total;
    }

    /** A deposit of {@code amount} was refused; the player is still owed it. */
    public void owe(UUID player, long amount) {
        if (amount > 0) {
            append(line("OWE", null, player, amount));
            owed.merge(player, amount, Long::sum);
        }
    }

    /**
     * Takes everything owed to the player off the books. Call before depositing; {@link #owe} it again if refused.
     *
     * @return the amount to deposit, 0 if nothing was owed
     */
    public long deliver(UUID player) {
        long amount = owed(player);
        if (amount > 0) {
            append(line("DELIVER", null, player, amount));
            owed.remove(player);
        }
        return amount;
    }

    /** Forces what was written to disk and closes the file. */
    @Override
    public void close() {
        synchronized (forceLock) {
            closed = true;
            forceLock.notifyAll();
        }
        if (forcer != null) {
            try {
                forcer.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (channel != null && channel.isOpen()) {
                channel.force(true);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not force the roulette stake journal", e);
        }
        closeChannel();
    }

    // ---- replay --------------------------------------------------------------------------------------------------

    private void load() throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path snapshot = snapshotFile();
        if (Files.exists(snapshot)) {
            // A rotation was interrupted. The snapshot is complete only if the journal it replaces is already gone.
            if (Files.exists(file)) {
                Files.delete(snapshot);
            } else {
                Files.move(snapshot, file, StandardCopyOption.ATOMIC_MOVE);
            }
        }
        if (Files.exists(file)) {
            byte[] bytes = Files.readAllBytes(file);
            boolean endsWithNewline = bytes.length == 0 || bytes[bytes.length - 1] == '\n';
            int number = 0;
            int skipped = 0;
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
            for (String line : lines) {
                number++;
                if (!endsWithNewline && number == lines.length) {
                    // Every event ends with a newline, so this one was cut off mid-write (power cut): its amount
                    // cannot be trusted even if it parses.
                    log.warning("Roulette stake journal: dropped the unfinished last line: " + line);
                    cutOffAfterLastNewline(bytes);
                    continue;
                }
                if (!line.isBlank() && !line.startsWith("#") && !replay(line.trim())) {
                    skipped++;
                    log.warning("Roulette stake journal: skipped line " + number + ": " + line);
                }
            }
            if (skipped > 0) {
                log.warning("Roulette stake journal: " + skipped + " unreadable line(s) in " + file + " were ignored.");
            }
        }
        boolean fresh = !Files.exists(file) || Files.size(file) == 0;
        openChannel();
        if (fresh) {
            write(HEADER + "\n");
        }
    }

    /** Removes a torn last line from the file, so the next event starts on a line of its own. */
    private void cutOffAfterLastNewline(byte[] bytes) throws IOException {
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] != '\n') {
            end--;
        }
        try (FileChannel whole = FileChannel.open(file, StandardOpenOption.WRITE)) {
            whole.truncate(end);
            whole.force(true);
        }
    }

    private boolean replay(String line) {
        String[] f = line.split("\\s+");
        try {
            if (f.length < 4) {
                return false;
            }
            switch (f[1]) {
                case "RESERVE" -> addHeld(UUID.fromString(f[2]), UUID.fromString(f[3]), amount(f, 4));
                case "REFUND", "PAY" -> {
                    UUID round = UUID.fromString(f[2]);
                    UUID player = UUID.fromString(f[3]);
                    if (f[1].equals("PAY")) {
                        amount(f, 5); // the payout must at least be there, or the line is torn
                    }
                    takeHeld(round, player, Math.min(amount(f, 4), held(round, player)));
                }
                case "VOID" -> {
                    UUID round = UUID.fromString(f[2]);
                    UUID player = UUID.fromString(f[3]);
                    long amount = Math.min(amount(f, 4), held(round, player));
                    takeHeld(round, player, amount);
                    if (amount > 0) {
                        owed.merge(player, amount, Long::sum);
                    }
                }
                case "OWE" -> owed.merge(UUID.fromString(f[2]), positive(amount(f, 3)), Long::sum);
                case "DELIVER" -> {
                    UUID player = UUID.fromString(f[2]);
                    long left = owed(player) - amount(f, 3);
                    if (left > 0) {
                        owed.put(player, left);
                    } else {
                        owed.remove(player);
                    }
                }
                default -> {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long amount(String[] fields, int index) {
        long amount = Long.parseLong(fields[index]);
        if (amount < 0) {
            throw new IllegalArgumentException("negative amount");
        }
        return amount;
    }

    private static long positive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        return amount;
    }

    private static void requirePositive(long amount) {
        positive(amount);
    }

    private void addHeld(UUID round, UUID player, long amount) {
        held.computeIfAbsent(round, r -> new LinkedHashMap<>()).merge(player, positive(amount), Long::sum);
    }

    private void takeHeld(UUID round, UUID player, long amount) {
        Map<UUID, Long> players = held.get(round);
        if (players == null) {
            return;
        }
        long left = players.getOrDefault(player, 0L) - amount;
        if (left > 0) {
            players.put(player, left);
        } else {
            players.remove(player);
            if (players.isEmpty()) {
                held.remove(round);
            }
        }
    }

    // ---- file ----------------------------------------------------------------------------------------------------

    private String line(String type, UUID round, UUID player, long amount) {
        return clock.getAsLong() + " " + type + (round == null ? "" : " " + round) + " " + player + " " + amount
                + (type.equals("PAY") ? "" : "\n");
    }

    /** Writes on the giving side: the money goes out whether or not the line could be written. */
    private void append(String text) {
        try {
            write(text.endsWith("\n") ? text : text + "\n");
        } catch (IOException e) {
            // logged in write(); memory stays right for as long as the server runs
        }
    }

    private void write(String text) throws IOException {
        try {
            if (channel == null || !channel.isOpen()) {
                openChannel();
            }
            ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            broken = false;
            synchronized (forceLock) {
                dirty = true;
                forceLock.notifyAll();
            }
        } catch (IOException e) {
            if (!broken) {
                broken = true;
                lastReopenAttempt = clock.getAsLong();
                log.log(Level.SEVERE, "Cannot write the roulette stake journal " + file
                        + ". Tables take no bets until it can be written again.", e);
            }
            throw e;
        }
    }

    private void openChannel() throws IOException {
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    private void closeChannel() {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            // nothing left to do with it
        }
    }

    private Path snapshotFile() {
        return file.resolveSibling(file.getFileName() + ".tmp");
    }

    /** The previous generation, kept as the audit trail. */
    Path previousFile() {
        return file.resolveSibling(file.getFileName() + ".1");
    }

    /**
     * With no round open the whole state is the owed amounts, so a large file can start over from them. The old
     * file is kept as one previous generation.
     */
    private void rotateIfIdle() {
        if (!held.isEmpty() || broken || closed) {
            return;
        }
        try {
            if (channel.size() <= rotateBytes) {
                return;
            }
            List<String> lines = new ArrayList<>();
            lines.add(HEADER);
            for (Map.Entry<UUID, Long> entry : owed.entrySet()) {
                lines.add(line("OWE", null, entry.getKey(), entry.getValue()).trim());
            }
            Path snapshot = snapshotFile();
            try (FileChannel out = FileChannel.open(snapshot, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    out.write(bytes);
                }
                out.force(true);
            }
            channel.force(true);
            channel.close();
            Files.move(file, previousFile(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(snapshot, file, StandardCopyOption.ATOMIC_MOVE);
            openChannel();
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not rotate the roulette stake journal; it keeps growing until this works", e);
            try {
                if (channel == null || !channel.isOpen()) {
                    openChannel();
                }
            } catch (IOException again) {
                broken = true;
                lastReopenAttempt = clock.getAsLong();
            }
        }
    }

    /** Group commit off the server thread: at most one {@code force} per interval, none while nothing is written. */
    private void startForcer() {
        forcer = new Thread(() -> {
            while (true) {
                synchronized (forceLock) {
                    while (!dirty && !closed) {
                        try {
                            forceLock.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (closed) {
                        return;
                    }
                    dirty = false;
                }
                try {
                    channel.force(false); // outside the lock: the server thread never waits for the disk
                } catch (IOException | RuntimeException e) {
                    // closed by a rotation, or a broken file that the next write reports
                }
                try {
                    Thread.sleep(FORCE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "Roulette-journal");
        forcer.setDaemon(true);
        forcer.start();
    }
}
