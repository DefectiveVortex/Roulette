package com.vortex.roulette.update;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.vortex.roulette.update.UpdateChecker.State.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckerTest {

    static final Set<String> PAPER = Set.of("bukkit", "spigot", "paper");
    static final Set<String> SPIGOT = Set.of("bukkit", "spigot");

    // ------------------------------------------------------------------ parsing and choosing (recorded fixture)

    static List<ModrinthVersion> fixture() throws IOException {
        try (InputStream in = UpdateCheckerTest.class.getResourceAsStream("/modrinth/versions.json")) {
            return ModrinthVersion.parseList(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static String pick(Channel channel, Set<String> loaders, String game) throws IOException {
        return UpdateChecker.pickLatest(fixture(), channel, loaders, game).map(ModrinthVersion::versionNumber).orElse(null);
    }

    @Test
    void parsesTheRecordedAnswer() throws IOException {
        List<ModrinthVersion> versions = fixture();
        assertEquals(6, versions.size());
        ModrinthVersion v = versions.stream().filter(x -> x.versionNumber().equals("1.1.0")).findFirst().orElseThrow();
        assertEquals("r2Rel110", v.id());
        assertEquals("release", v.versionType());
        assertEquals(List.of("1.20.1", "1.21.4", "26.3"), v.gameVersions());
        assertEquals("Roulette-1.1.0.jar", v.file().filename(), "the primary jar, not the sources zip listed first");
        assertEquals("4455", v.file().sha512());
        assertEquals(408000, v.file().size());
        assertTrue(v.file().url().endsWith("/Roulette-1.1.0.jar"));
    }

    @Test
    void releaseChannelFiltersByGameVersionAndLoader() throws IOException {
        assertEquals("1.1.0", pick(Channel.RELEASE, PAPER, "1.21.4"), "9.9.9 is Fabric-only, 1.1.2 is 26.3-only");
        assertEquals("1.1.2", pick(Channel.RELEASE, PAPER, "26.3"));
        assertEquals("1.1.0", pick(Channel.RELEASE, SPIGOT, "1.20.1"));
        assertNull(pick(Channel.RELEASE, PAPER, "1.19.4"));
    }

    @Test
    void betaAndAlphaAreOptIn() throws IOException {
        assertEquals("1.2.0-beta.2", pick(Channel.BETA, PAPER, "1.21.4"));
        assertEquals("1.3.0-alpha.1", pick(Channel.ALPHA, PAPER, "1.21.4"));
        assertEquals("1.2.0-beta.2", pick(Channel.ALPHA, SPIGOT, "1.21.4"), "the alpha is Paper/Purpur only");
        assertEquals("1.1.2", pick(Channel.ALPHA, PAPER, "26.3"));
        assertTrue(Channel.BETA.accepts("release"));
        assertFalse(Channel.RELEASE.accepts("beta"));
        assertFalse(Channel.ALPHA.accepts("snapshot"));
        assertEquals(Channel.BETA, Channel.parse(" Beta "));
        assertNull(Channel.parse("nightly"));
    }

    @Test
    void gameVersionComesFromTheBukkitVersion() {
        assertEquals("1.21.4", UpdateChecker.gameVersionOf("1.21.4-R0.1-SNAPSHOT"));
        assertEquals("26.3", UpdateChecker.gameVersionOf("26.3-R0.1-SNAPSHOT"));
        assertEquals("1.20.1", UpdateChecker.gameVersionOf("1.20.1"));
    }

    // ------------------------------------------------------------------ against a local mock of Modrinth

    @TempDir Path dir;
    HttpServer mock;
    final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    final List<String> userAgents = new ArrayList<>();
    final List<LogRecord> logs = new ArrayList<>();
    final byte[] jar = "PK\u0003\u0004 pretend this is Roulette 1.1.0".getBytes(StandardCharsets.UTF_8);
    String advertisedHash;
    int versionStatus = 200;

    void startMock() throws IOException {
        advertisedHash = advertisedHash == null ? sha512(jar) : advertisedHash;
        mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/v2/project/", ex -> {
            count(ex);
            String body = """
                [{"id":"r2Rel110","version_number":"1.1.0","version_type":"release",
                  "game_versions":["1.21.4"],"loaders":["paper","spigot"],"date_published":"2026-10-10T09:00:00Z",
                  "files":[{"hashes":{"sha512":"%s"},"url":"%s/files/Roulette-1.1.0.jar","filename":"Roulette-1.1.0.jar",
                            "primary":true,"size":%d}]}]"""
                .formatted(advertisedHash, base(), jar.length);
            reply(ex, versionStatus, versionStatus == 200 ? body.getBytes(StandardCharsets.UTF_8) : new byte[0]);
        });
        mock.createContext("/files/", ex -> {
            count(ex);
            reply(ex, 200, jar);
        });
        mock.start();
    }

    String base() {
        return "http://127.0.0.1:" + mock.getAddress().getPort();
    }

    void count(HttpExchange ex) {
        hits.computeIfAbsent(ex.getRequestURI().getPath(), k -> new AtomicInteger()).incrementAndGet();
        synchronized (userAgents) {
            userAgents.add(ex.getRequestHeaders().getFirst("User-Agent"));
        }
    }

    static void reply(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    int jarHits() {
        AtomicInteger n = hits.get("/files/Roulette-1.1.0.jar");
        return n == null ? 0 : n.get();
    }

    @AfterEach
    void stopMock() {
        if (mock != null) mock.stop(0);
    }

    Path updateFolder() {
        return dir.resolve("plugins/update");
    }

    UpdateChecker checker(String running) {
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.setLevel(Level.ALL);
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { logs.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        return new UpdateChecker("TESTPROJ", new UpdateChecker.Server(running, PAPER, "1.21.4"),
            updateFolder(), "Roulette-1.0.jar", dir.resolve("plugins/Roulette-1.0.jar"), log);
    }

    UpdateSettings settings(String api) {
        return new UpdateSettings(true, true, Channel.RELEASE, 12, api);
    }

    List<LogRecord> loud() {
        return logs.stream().filter(r -> r.getLevel().intValue() > Level.INFO.intValue()).toList();
    }

    List<Path> updateFiles() throws IOException {
        if (!Files.isDirectory(updateFolder())) return List.of();
        try (Stream<Path> s = Files.list(updateFolder())) {
            return s.toList();
        }
    }

    @Test
    void downloadsVerifiesAndStagesUnderTheRunningJarsName() throws IOException {
        startMock();
        UpdateChecker.Result r = checker("1.0").check(settings(base() + "/v2"), true);
        assertEquals(STAGED, r.state());
        assertTrue(r.newlyStaged());
        assertEquals("1.1.0", r.latest().versionNumber());
        assertEquals(List.of(updateFolder().resolve("Roulette-1.0.jar")), updateFiles(), "only the jar, no temp file");
        assertArrayEquals(jar, Files.readAllBytes(updateFolder().resolve("Roulette-1.0.jar")));
        assertTrue(userAgents.stream().allMatch("DefectiveVortex/Roulette/1.0"::equals), userAgents.toString());
        assertTrue(hits.containsKey("/v2/project/TESTPROJ/version"));
        assertTrue(loud().isEmpty());
    }

    @Test
    void aHashMismatchLeavesNothingBehind() throws IOException {
        advertisedHash = "00".repeat(64);
        startMock();
        UpdateChecker.Result r = checker("1.0").check(settings(base() + "/v2"), true);
        assertEquals(DOWNLOAD_FAILED, r.state());
        assertEquals(1, jarHits());
        assertEquals(List.of(), updateFiles());
        assertEquals(1, loud().size());
        assertTrue(loud().get(0).getMessage().contains("sha512 mismatch"), loud().get(0).getMessage());
    }

    @Test
    void anAlreadyStagedVersionIsNotDownloadedAgain() throws IOException {
        startMock();
        UpdateChecker checker = checker("1.0");
        assertTrue(checker.check(settings(base() + "/v2"), true).newlyStaged());
        UpdateChecker.Result again = checker.check(settings(base() + "/v2"), true);
        assertEquals(STAGED, again.state());
        assertFalse(again.newlyStaged());
        assertEquals(1, jarHits());
        // a fresh checker (after a restart without applying) sees it too
        assertEquals(STAGED, checker("1.0").check(settings(base() + "/v2"), true).state());
        assertEquals(1, jarHits());
    }

    @Test
    void aStaleStagedJarIsReplaced() throws IOException {
        startMock();
        Files.createDirectories(updateFolder());
        Files.writeString(updateFolder().resolve("Roulette-1.0.jar"), "an older staged build");
        UpdateChecker.Result r = checker("1.0").check(settings(base() + "/v2"), true);
        assertTrue(r.newlyStaged());
        assertArrayEquals(jar, Files.readAllBytes(updateFolder().resolve("Roulette-1.0.jar")));
    }

    @Test
    void sameOrOlderVersionDownloadsNothing() throws IOException {
        startMock();
        assertEquals(UP_TO_DATE, checker("1.1.0").check(settings(base() + "/v2"), true).state());
        assertEquals(UP_TO_DATE, checker("1.2").check(settings(base() + "/v2"), true).state());
        assertEquals(0, jarHits());
        assertEquals(List.of(), updateFiles());
    }

    @Test
    void withoutAutoDownloadItOnlyReports() throws IOException {
        startMock();
        UpdateChecker.Result r = checker("1.0").check(settings(base() + "/v2"), false);
        assertEquals(AVAILABLE, r.state());
        assertEquals("1.1.0", r.latest().versionNumber());
        assertEquals(0, jarHits());
    }

    @Test
    void anUnpublishedProjectMeansNoUpdate() throws IOException {
        versionStatus = 404;
        startMock();
        UpdateChecker.Result r = checker("1.0").check(settings(base() + "/v2"), true);
        assertEquals(UP_TO_DATE, r.state());
        assertNull(r.latest());
        assertTrue(loud().isEmpty());
    }

    @Test
    void serverErrorsAndDeadNetworksFailQuietly() throws IOException {
        versionStatus = 503;
        startMock();
        assertEquals(CHECK_FAILED, checker("1.0").check(settings(base() + "/v2"), true).state());
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        assertEquals(CHECK_FAILED, checker("1.0").check(settings("http://127.0.0.1:" + deadPort + "/v2"), true).state());
        assertTrue(loud().isEmpty(), "only FINE logging");
        assertTrue(logs.stream().noneMatch(r -> r.getThrown() != null), "no stack traces");
        assertEquals(List.of(), updateFiles());
    }

    @Test
    void neverWritesTheRunningJar() throws IOException {
        startMock();
        Path running = updateFolder().resolve("Roulette-1.0.jar");
        Files.createDirectories(updateFolder());
        Files.writeString(running, "running");
        UpdateChecker checker = new UpdateChecker("TESTPROJ", new UpdateChecker.Server("1.0", PAPER, "1.21.4"),
            updateFolder(), "Roulette-1.0.jar", running, Logger.getAnonymousLogger());
        assertEquals(DOWNLOAD_FAILED, checker.check(settings(base() + "/v2"), true).state());
        assertEquals("running", Files.readString(running));
    }

    @Test
    void settingsFallBackToDefaults() {
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("updates.channel", "nightly");
        yaml.set("updates.interval-hours", 0);
        yaml.set("updates.api-url", "http://172.17.0.1:25590/v2/");
        UpdateSettings s = UpdateSettings.from(yaml.getConfigurationSection("updates"));
        assertEquals(Channel.RELEASE, s.channel());
        assertEquals(1, s.intervalHours());
        assertTrue(s.check() && s.autoDownload());
        assertEquals("http://172.17.0.1:25590/v2", s.apiUrl());
        assertEquals(UpdateSettings.DEFAULT_API, UpdateSettings.from(null).apiUrl());
    }

    static String sha512(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
