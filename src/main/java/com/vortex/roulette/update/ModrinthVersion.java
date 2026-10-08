package com.vortex.roulette.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One entry of Modrinth's {@code GET /v2/project/{id}/version} answer, reduced to what the updater needs.
 *
 * @param file the primary file (or the first jar when none is marked primary); null if there is no jar
 */
public record ModrinthVersion(String id, String versionNumber, String versionType, List<String> gameVersions,
                              List<String> loaders, String datePublished, FileInfo file) {

    public record FileInfo(String url, String filename, String sha512, long size) {}

    /** Whether this version runs on a server with one of these loaders and exactly this game version. */
    public boolean supports(Set<String> serverLoaders, String gameVersion) {
        return loaders.stream().anyMatch(serverLoaders::contains) && gameVersions.contains(gameVersion);
    }

    /** Parse the answer; entries missing a version number are skipped. */
    public static List<ModrinthVersion> parseList(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) throw new IllegalArgumentException("expected a JSON array of versions");
        List<ModrinthVersion> versions = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String number = string(o, "version_number");
            if (number == null || number.isBlank()) continue;
            versions.add(new ModrinthVersion(string(o, "id"), number,
                lower(string(o, "version_type")), strings(o, "game_versions"), lowerAll(strings(o, "loaders")),
                string(o, "date_published"), primaryFile(o)));
        }
        return versions;
    }

    private static FileInfo primaryFile(JsonObject version) {
        if (!version.has("files") || !version.get("files").isJsonArray()) return null;
        JsonObject chosen = null;
        for (JsonElement e : version.getAsJsonArray("files")) {
            if (!e.isJsonObject()) continue;
            JsonObject f = e.getAsJsonObject();
            String name = string(f, "filename");
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            if (f.has("primary") && f.get("primary").isJsonPrimitive() && f.get("primary").getAsBoolean()) {
                chosen = f;
                break;
            }
            if (chosen == null) chosen = f;
        }
        if (chosen == null) return null;
        String sha512 = null;
        if (chosen.has("hashes") && chosen.get("hashes").isJsonObject()) sha512 = lower(string(chosen.getAsJsonObject("hashes"), "sha512"));
        long size = chosen.has("size") && chosen.get("size").isJsonPrimitive() ? chosen.get("size").getAsLong() : -1;
        return new FileInfo(string(chosen, "url"), string(chosen, "filename"), sha512, size);
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = o.get(key);
        if (e != null && e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (JsonElement item : a) if (item.isJsonPrimitive()) out.add(item.getAsString());
        }
        return List.copyOf(out);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static List<String> lowerAll(List<String> list) {
        return list.stream().map(ModrinthVersion::lower).toList();
    }
}
