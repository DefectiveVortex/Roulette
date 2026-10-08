package com.vortex.roulette;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * resourcepack/contract.json, the numbers of docs/ART-CONTRACT.md, for tests that pin the code's geometry to it.
 * The pictures are drawn from that file, so a test that fails here means the code and the art no longer agree.
 */
public final class ArtContract {

    private ArtContract() {}

    public static JsonObject load() {
        return read(Path.of("resourcepack", "contract.json"));
    }

    public static JsonObject read(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A JSON array of numbers as doubles. */
    public static double[] numbers(JsonArray array) {
        double[] out = new double[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).getAsDouble();
        }
        return out;
    }
}
