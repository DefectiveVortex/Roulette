package com.vortex.roulette.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.vortex.roulette.display.WheelView.SoundSpec;
import org.junit.jupiter.api.Test;

class SoundSpecTest {

    @Test
    void readsSoundVolumeAndPitch() {
        assertEquals(new SoundSpec("block.stone_button.click_on", 0.5f, 1.5f),
                SoundSpec.parse("  block.stone_button.click_on   0.5 1.5 "));
        assertEquals(new SoundSpec("minecraft:ui.button.click", 1f, 1f), SoundSpec.parse("minecraft:ui.button.click"));
        assertEquals(new SoundSpec("a", 0.2f, 2f), SoundSpec.parse("a 0.2 9"));
    }

    @Test
    void anEmptyOrBrokenEntryIsSilence() {
        assertNull(SoundSpec.parse(null));
        assertNull(SoundSpec.parse("   "));
        assertNull(SoundSpec.parse("block.stone_button.click_on loud"));
    }
}
