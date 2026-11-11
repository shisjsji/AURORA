package com.aurora.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for turning a six-point curve into a wider band count.
 *
 * <p>The settings format is a six-point curve and stays that way; the engine now hands the DSP ten
 * bands. Everything in between is arithmetic that can be checked without an effect: the derived
 * table must stay ordered and inside the authored ends, and the curve must keep its shape.
 */
public class ExpandTest {

    private static final float[] TABLE = AudioEq.BAND_HZ;

    @Test
    public void sameCountReturnsTheSameTable() {
        float[] out = AudioEq.expandTable(TABLE, AudioEq.BANDS);
        assertEquals("six asked for, six given", AudioEq.BANDS, out.length);
        for (int i = 0; i < out.length; i++) {
            assertEquals("band " + i, TABLE[i], out[i], 0.001f);
        }
    }

    @Test
    public void tenBandsKeepTheAuthoredEnds() {
        float[] out = AudioEq.expandTable(TABLE, 10);
        assertEquals(10, out.length);
        assertEquals("the bottom cutoff is the one that was authored", TABLE[0], out[0], 0.001f);
        assertEquals("the top cutoff is the one that was authored",
                TABLE[TABLE.length - 1], out[9], 0.001f);
    }

    @Test
    public void expandedTableIsStrictlyIncreasing() {
        float[] out = AudioEq.expandTable(TABLE, 12);
        for (int i = 1; i < out.length; i++) {
            assertTrue("band " + i + " (" + out[i] + ") must sit above band " + (i - 1)
                    + " (" + out[i - 1] + ")", out[i] > out[i - 1]);
        }
    }

    @Test
    public void expandedTableIsLogEvenlySpaced() {
        float[] out = AudioEq.expandTable(new float[]{100f, 10000f}, 5);
        // Two decades over four steps: every step multiplies the cutoff by sqrt(10).
        double ratio = Math.sqrt(10.0);
        for (int i = 1; i < out.length; i++) {
            assertEquals("step " + i + " must be the same ratio", ratio,
                    (double) out[i] / out[i - 1], 0.01);
        }
    }

    @Test
    public void degenerateInputsAreHandled() {
        assertEquals("no bands asked for", 0, AudioEq.expandTable(TABLE, 0).length);
        assertEquals("null table", 0, AudioEq.expandTable(null, 10).length);
        float[] single = AudioEq.expandTable(TABLE, 1);
        assertEquals(1, single.length);
        assertEquals("one band covers up to the top cutoff", TABLE[TABLE.length - 1],
                single[0], 0.001f);
    }

    @Test
    public void bandCentresSitInsideTheirInterval() {
        float[] centres = AudioEq.bandCentres(TABLE);
        for (int i = 0; i < centres.length; i++) {
            float low = i == 0 ? TABLE[0] / 2f : TABLE[i - 1];
            assertTrue("centre of band " + i + " must be above its lower edge",
                    centres[i] > low);
            assertTrue("centre of band " + i + " must be at or below its upper edge",
                    centres[i] <= TABLE[i]);
        }
    }

    @Test
    public void aFlatCurveStaysFlat() {
        float[] flat = new float[AudioEq.BANDS];
        float[] out = AudioEq.expandGains(flat, TABLE, 10);
        assertEquals(10, out.length);
        for (int i = 0; i < out.length; i++) {
            assertEquals("band " + i + " must stay at 0 dB", 0f, out[i], 0.001f);
        }
    }

    @Test
    public void theSameCountIsAnIdentity() {
        float[] curve = {5f, 3f, -2f, -4f, 1f, 6f};
        float[] out = AudioEq.expandGains(curve, TABLE, AudioEq.BANDS);
        for (int i = 0; i < curve.length; i++) {
            assertEquals("band " + i, curve[i], out[i], 0.001f);
        }
    }

    @Test
    public void aTiltKeepsItsDirectionAndItsEnds() {
        // A straight downward tilt in dB per band: every expanded point must stay between the
        // neighbours it was interpolated from, and the outermost ones must match the source.
        float[] curve = {6f, 4f, 2f, 0f, -2f, -4f};
        float[] out = AudioEq.expandGains(curve, TABLE, 10);
        assertTrue("the low end stays a boost", out[0] > 3.5f);
        assertTrue("the high end stays a cut", out[9] < -2.5f);
        for (int i = 1; i < out.length; i++) {
            assertTrue("the tilt must not reverse at band " + i, out[i] <= out[i - 1] + 0.001f);
        }
    }

    @Test
    public void aBoostedBandStaysBoostedAcrossTheExpansion() {
        float[] curve = {0f, 0f, 0f, 6f, 6f, 6f};
        float[] out = AudioEq.expandGains(curve, TABLE, 10);
        assertTrue("1500 Hz must survive as a boost", out[6] > 4f);
        assertEquals("80 Hz must stay untouched", 0f, out[0], 0.001f);
    }

    @Test
    public void nullCurveGivesNull() {
        assertNull("no curve, nothing to resample", AudioEq.expandGains(null, TABLE, 10));
    }
}
