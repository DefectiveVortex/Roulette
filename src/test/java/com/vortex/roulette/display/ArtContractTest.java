package com.vortex.roulette.display;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vortex.roulette.ArtContract;
import com.vortex.roulette.model.Pocket;
import com.vortex.roulette.model.PocketColor;
import com.vortex.roulette.model.WheelType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The wheel code against the art contract: what the artist was told has to be what the code does. */
class ArtContractTest {

    private final JsonObject contract = ArtContract.load();
    private final JsonObject wheel = contract.getAsJsonObject("wheel");
    private final JsonObject world = contract.getAsJsonObject("world");

    @Test
    void thePocketOrderDrawnOnTheRotorIsTheModels() {
        for (WheelType type : WheelType.values()) {
            List<String> drawn = new ArrayList<>();
            for (JsonElement label : contract.getAsJsonObject("pockets")
                    .getAsJsonArray(type.name().toLowerCase(Locale.ROOT))) {
                drawn.add(label.getAsString());
            }
            assertEquals(type.pockets().stream().map(Pocket::label).toList(), drawn, type.name());
        }
    }

    @Test
    void theRedNumbersAreTheModels() {
        List<Integer> drawn = new ArrayList<>();
        contract.getAsJsonArray("red").forEach(number -> drawn.add(number.getAsInt()));
        List<Integer> red = WheelType.EUROPEAN.pockets().stream()
                .filter(pocket -> pocket.color() == PocketColor.RED)
                .map(pocket -> Integer.parseInt(pocket.label())).sorted().toList();
        assertEquals(red, drawn);
    }

    @Test
    void theBallRunsAndRestsWhereTheRingsAreDrawn() {
        double size = wheel.getAsJsonArray("size").get(0).getAsDouble();
        assertEquals(wheel.get("ball_on_track").getAsDouble() / size, SpinCurve.TRACK_RADIUS, 1e-12);
        assertEquals(wheel.get("ball_at_rest").getAsDouble() / size, SpinCurve.REST_RADIUS, 1e-12);
    }

    @Test
    void theWheelIsLaidOutAtTheSizeAndDensityOfTheFelt() {
        JsonObject felt = contract.getAsJsonObject("felt");
        double size = wheel.getAsJsonArray("size").get(0).getAsDouble();
        assertEquals(world.get("wheel_blocks").getAsDouble(), WheelView.WIDTH, 0);
        assertEquals(felt.get("cell").getAsDouble() / world.get("blocks_per_cell").getAsDouble(),
                size / WheelView.WIDTH, 1e-9, "pixels per block of felt and wheel");
    }

    @Test
    void theBallModelHoldsTheBallWhereTheViewExpectsIt() {
        JsonObject model = world.getAsJsonObject("ball_model_sixteenths");
        assertEquals(model.get("orbit").getAsDouble() / 16, WheelView.BALL_ORBIT, 1e-6);

        JsonObject element = ArtContract.read(Path.of("resourcepack/assets/roulette/models/item/ball.json"))
                .getAsJsonArray("elements").get(0).getAsJsonObject();
        double[] from = ArtContract.numbers(element.getAsJsonArray("from"));
        double[] to = ArtContract.numbers(element.getAsJsonArray("to"));
        double half = model.get("size").getAsDouble() / 2;
        double orbit = model.get("orbit").getAsDouble();
        assertArrayEquals(new double[] {8 - half, 8, 8 + orbit - half}, from, 0, "run the generator again");
        assertArrayEquals(new double[] {8 + half, 8, 8 + orbit + half}, to, 0, "run the generator again");
    }
}
