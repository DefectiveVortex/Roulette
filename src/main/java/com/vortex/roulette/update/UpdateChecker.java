package com.vortex.roulette.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Asks Modrinth for the newest Roulette build that fits this server and, if asked to, stages its jar in the
 * update folder so the server installs it on the next restart. Pure Java (no Bukkit), blocking, and meant
 * to run off the main thread. Network trouble never throws and only logs at FINE.
 */
public final class UpdateChecker {

    /**
     * The Modrinth project's id and slug. Both are empty until Roulette is published there: while they are,
     * {@link #isPublished()} is false and nothing is checked or downloaded. Fill them in from the real project
     * page; a guessed slug could belong to someone else's plugin.
     */
    public static final String PROJECT_ID = "";
    public static final String PROJECT_SLUG = "";

    /** Whether this build knows its Modrinth project. */
    public static boolean isPublished() {
        return !PROJECT_ID.isBlank() && !PROJECT_SLUG.isBlank();
    }

    static final long MAX_JAR_BYTES = 64L << 20;
    private static final Duration API_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(2);

    public enum State { NOT_CHECKED, UP_TO_DATE, AVAILABLE, STAGED, CHECK_FAILED, DOWNLOAD_FAILED }

    /**
     * What a check found.
     *
     * @param latest       the newest version on the channel that fits this server; null if none (or the check failed)
     * @param newlyStaged  true when this check downloaded the jar (not when it was already staged)
     */
    public record Result(State state, ModrinthVersion latest, boolean newlyStaged) {
        public static final Result NOT_CHECKED = new Result(State.NOT_CHECKED, null, false);
    }

    /** What the running server is: its Roulette version, the loaders it can run and its Minecraft version. */
    public record Server(String currentVersion, Set<String> loaders, String gameVersion) {}

    static final class HashMismatchException extends IOException {
        HashMismatchException(String expected, String actual) {
            super("sha512 mismatch (expected " + abbreviate(expected) + ", got " + abbreviate(actual) + ")");
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private final String projectId;
    private final Server server;
    private final Path updateFolder;
    private final String jarName;
    private final Path runningJar;
    private final Logger log;
    private final String userAgent;

    /**
     * @param updateFolder where Bukkit picks up new jars (plugins/update)
     * @param jarName      the running jar's file name: Bukkit only swaps in an update with the same name
     * @param runningJar   the running jar, never written to; may be null
     */
    public UpdateChecker(String projectId, Server server, Path updateFolder, String jarName, Path runningJar, Logger log) {
        this.projectId = projectId;
        this.server = server;
        this.updateFolder = updateFolder;
        this.jarName = jarName;
        this.runningJar = runningJar;
        this.log = log;
        this.userAgent = "DefectiveVortex/Roulette/" + server.currentVersion();
    }

    public Path stagedFile() {
        return updateFolder.resolve(jarName);
    }

    /** Check now and, when {@code download} is set, stage a newer jar. Never throws. */
    public synchronized Result check(UpdateSettings settings, boolean download) {
        List<ModrinthVersion> versions;
        try {
            versions = fetch(settings.apiUrl());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(State.CHECK_FAILED, null, false);
        } catch (IOException | RuntimeException e) {
            log.log(Level.FINE, "Update check failed: " + e);
            return new Result(State.CHECK_FAILED, null, false);
        }

        Optional<ModrinthVersion> best = pickLatest(versions, settings.channel(), server.loaders(), server.gameVersion());
        if (best.isEmpty() || !Versions.isNewer(best.get().versionNumber(), server.currentVersion())) {
            log.fine("Roulette " + server.currentVersion() + " is up to date (" + settings.channel().id() + " channel).");
            return new Result(State.UP_TO_DATE, best.orElse(null), false);
        }
        ModrinthVersion latest = best.get();
        if (isStaged(latest)) {
            log.fine("Roulette " + latest.versionNumber() + " is already staged in " + stagedFile());
            return new Result(State.STAGED, latest, false);
        }
        if (!download) return new Result(State.AVAILABLE, latest, false);

        try {
            download(latest);
            log.info("Downloaded Roulette " + latest.versionNumber() + " (sha512 verified) to "
                + updateFolder.getFileName() + "/" + jarName + "; restart the server to apply.");
            return new Result(State.STAGED, latest, true);
        } catch (HashMismatchException e) {
            log.warning("Rejected the Roulette " + latest.versionNumber() + " download: " + e.getMessage()
                + ". Nothing was installed.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            log.log(Level.FINE, "Downloading Roulette " + latest.versionNumber() + " failed: " + e);
        }
        return new Result(State.DOWNLOAD_FAILED, latest, false);
    }

    /** Modrinth's versions of the project for this server. A 404 (project not public yet) means none. */
    List<ModrinthVersion> fetch(String apiBase) throws IOException, InterruptedException {
        String query = "?loaders=" + jsonArrayParam(server.loaders())
            + "&game_versions=" + jsonArrayParam(Set.of(server.gameVersion()));
        HttpResponse<String> response = http.send(request(apiBase + "/project/" + projectId + "/version" + query, API_TIMEOUT),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            log.fine("Roulette isn't published on Modrinth yet (404).");
            return List.of();
        }
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        return ModrinthVersion.parseList(response.body());
    }

    /** The newest version on {@code channel} with a verifiable jar that runs on this server. */
    public static Optional<ModrinthVersion> pickLatest(List<ModrinthVersion> versions, Channel channel,
                                                       Set<String> loaders, String gameVersion) {
        return versions.stream()
            .filter(v -> channel.accepts(v.versionType()))
            .filter(v -> v.supports(loaders, gameVersion))
            .filter(v -> v.file() != null && v.file().url() != null && v.file().sha512() != null)
            .max(Comparator.<ModrinthVersion, String>comparing(ModrinthVersion::versionNumber, Versions::compare)
                .thenComparing(v -> v.datePublished() == null ? "" : v.datePublished()));
    }

    /** Whether this exact jar is already waiting in the update folder. */
    public boolean isStaged(ModrinthVersion version) {
        Path file = stagedFile();
        if (version.file() == null || version.file().sha512() == null || !Files.isRegularFile(file)) return false;
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest sha = sha512();
            in.transferTo(new DigestSink(sha, Long.MAX_VALUE));
            return hex(sha).equalsIgnoreCase(version.file().sha512());
        } catch (IOException e) {
            return false;
        }
    }

    /** Download to a temp file beside the target, verify the hash, then move it into place in one step. */
    Path download(ModrinthVersion version) throws IOException, InterruptedException {
        ModrinthVersion.FileInfo file = version.file();
        if (file == null || file.url() == null || file.sha512() == null) throw new IOException("no verifiable jar");
        Path target = stagedFile().toAbsolutePath().normalize();
        if (runningJar != null && target.equals(runningJar.toAbsolutePath().normalize())) {
            throw new IOException("update folder resolves to the running jar");
        }
        Files.createDirectories(updateFolder);
        Path temp = Files.createTempFile(updateFolder, ".roulette-update-", ".part");
        try {
            HttpResponse<InputStream> response = http.send(request(file.url(), DOWNLOAD_TIMEOUT),
                HttpResponse.BodyHandlers.ofInputStream());
            MessageDigest sha = sha512();
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                try (OutputStream out = Files.newOutputStream(temp)) {
                    in.transferTo(new DigestSink(sha, MAX_JAR_BYTES, out));
                }
            }
            String actual = hex(sha);
            if (!actual.equalsIgnoreCase(file.sha512())) throw new HashMismatchException(file.sha512(), actual);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private HttpRequest request(String url, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .GET()
            .build();
    }

    /** Bukkit's "1.21.4-R0.1-SNAPSHOT" (or "26.3-...") as Modrinth's game version "1.21.4". */
    public static String gameVersionOf(String bukkitVersion) {
        int dash = bukkitVersion.indexOf('-');
        return dash >= 0 ? bukkitVersion.substring(0, dash) : bukkitVersion;
    }

    private static String jsonArrayParam(Set<String> values) {
        String json = values.stream().sorted().map(v -> "\"" + v + "\"").collect(Collectors.joining(",", "[", "]"));
        return URLEncoder.encode(json, StandardCharsets.UTF_8);
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String abbreviate(String hash) {
        return hash == null ? "none" : hash.length() > 12 ? hash.substring(0, 12) + "…" : hash;
    }

    /** Feeds a digest, optionally copies to {@code out}, and gives up past {@code limit} bytes. */
    private static final class DigestSink extends OutputStream {
        private final MessageDigest digest;
        private final long limit;
        private final OutputStream out;
        private long written;

        DigestSink(MessageDigest digest, long limit) {
            this(digest, limit, null);
        }

        DigestSink(MessageDigest digest, long limit, OutputStream out) {
            this.digest = digest;
            this.limit = limit;
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            written += len;
            if (written > limit) throw new IOException("download larger than " + limit + " bytes");
            digest.update(b, off, len);
            if (out != null) out.write(b, off, len);
        }
    }
}
