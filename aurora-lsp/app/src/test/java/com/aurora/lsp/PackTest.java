package com.aurora.lsp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Tests for the device-pack rules.
 *
 * <p>A pack arrives over HTTPS from a server and lands straight in the audio chain, so every field
 * is checked here. These assertions pin the shape of that check: a bad table is dropped whole
 * rather than half applied, and an out-of-range limiter is ignored rather than clamped, because a
 * clamped 40:1 is still a limiter nobody asked for.
 */
public class PackTest {

    private static final double[] GOOD_HZ = {80, 200, 500, 1500, 4000, 10000};
    private static final double[] GOOD_BOOST = {8, 4, 6, 8, 8, 8};

    @Test
    public void goodTableIsAccepted() {
        assertArrayEquals(new float[]{80, 200, 500, 1500, 4000, 10000},
                AuroraEngine.bandHzFromPack(GOOD_HZ), 0.01f);
    }

    @Test
    public void wrongLengthIsRejected() {
        assertNull("five bands cannot fill a six-band chain",
                AuroraEngine.bandHzFromPack(new double[]{80, 200, 500, 1500, 4000}));
        assertNull("seven bands are just as wrong",
                AuroraEngine.bandHzFromPack(new double[]{80, 200, 500, 1500, 4000, 10000, 14000}));
        assertNull(AuroraEngine.bandHzFromPack(null));
    }

    @Test
    public void outOfAudibleWindowIsRejected() {
        assertNull("20 Hz is the edge, not inside it",
                AuroraEngine.bandHzFromPack(new double[]{20, 200, 500, 1500, 4000, 10000}));
        assertNull("20 kHz is the edge, not inside it",
                AuroraEngine.bandHzFromPack(new double[]{80, 200, 500, 1500, 4000, 20000}));
    }

    @Test
    public void unorderedTableIsRejected() {
        // 1500 and 4000 swapped: the filters would land on the wrong notes, which sounds close
        // enough to work and is exactly why it must not be half accepted.
        assertNull(AuroraEngine.bandHzFromPack(new double[]{80, 200, 500, 4000, 1500, 10000}));
        assertNull("two bands on one frequency is not a table",
                AuroraEngine.bandHzFromPack(new double[]{80, 200, 500, 500, 4000, 10000}));
    }

    @Test
    public void boostCeilingIsAcceptedAndBounded() {
        assertArrayEquals(new float[]{8, 4, 6, 8, 8, 8},
                AuroraEngine.maxBoostFromPack(GOOD_BOOST), 0.01f);
        assertNull("a negative ceiling would mute the band",
                AuroraEngine.maxBoostFromPack(new double[]{8, 4, 6, 8, 8, -1}));
        assertNull("+12 dB is the ceiling of the whole chain",
                AuroraEngine.maxBoostFromPack(new double[]{8, 4, 6, 8, 8, 12.5}));
        assertNull(AuroraEngine.maxBoostFromPack(new double[]{8, 4, 6}));
    }

    @Test
    public void limiterNeedsAllFourNumbers() {
        assertNotNull(AuroraEngine.limiterFromPack(-6, 6, 5, 80));
        assertNull("a pack that only sets the threshold is not a limiter",
                AuroraEngine.limiterFromPack(-6, Double.NaN, 5, 80));
        assertNull("no ratio means no limiter",
                AuroraEngine.limiterFromPack(-6, 6, Double.NaN, 80));
    }

    @Test
    public void limiterOutsideTheHonourableWindowIsIgnored() {
        assertNull("0 dBFS is a brick wall, not a limiter",
                AuroraEngine.limiterFromPack(3, 6, 5, 80));
        assertNull(AuroraEngine.limiterFromPack(-30, 6, 5, 80));
        assertNull("ratio below 1 would expand instead of limit",
                AuroraEngine.limiterFromPack(-6, 0.5, 5, 80));
        assertNull(AuroraEngine.limiterFromPack(-6, 25, 5, 80));
        assertNull("a 0 ms attack makes the limiter chatter",
                AuroraEngine.limiterFromPack(-6, 6, 0, 80));
        assertNull(AuroraEngine.limiterFromPack(-6, 6, 5, 10));
    }

    @Test
    public void limiterKeepsTheValuesItWasGiven() {
        AudioEq.LimiterConfig config = AuroraEngine.limiterFromPack(-6, 6, 5, 80);
        assertNotNull(config);
        assertEquals(-6f, config.thresholdDb, 0.001f);
        assertEquals(6f, config.ratio, 0.001f);
        assertEquals(5f, config.attackMs, 0.001f);
        assertEquals(80f, config.releaseMs, 0.001f);
    }
}
