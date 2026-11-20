package com.aurora.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the curve maths.
 *
 * <p>These three assertions cover the three steps that have already caused user-visible bugs: a
 * flat curve must stay flat, a peak must be fitted to the ceiling instead of overshooting, and a
 * quiet volume must lift the bottom band. Each one fails if its step is inverted or skipped.
 */
public class CurveTest {

    private static AuroraEngine.Config config(float[] curve, int strength) {
        AuroraEngine.Config c = new AuroraEngine.Config();
        c.enabled = true;
        c.userCurve = curve;
        c.strength = strength;
        return c;
    }

    /** uid -1 and device -1 mean "no app, unknown route", i.e. no trimming. */
    private static AuroraEngine.SessionInfo speaker() {
        return new AuroraEngine.SessionInfo(1, -1, -1, null);
    }

    @Test
    public void flatCurveStaysFlat() {
        float[] out = AuroraEngine.buildGains(
                config(new float[]{0, 0, 0, 0, 0, 0}, 100), speaker(), 100);
        for (float gain : out) {
            assertEquals(0f, gain, 0.01f);
        }
    }

    @Test
    public void peakIsCappedNotOvershot() {
        // 80 Hz asks for +10. The preamp shifts the whole curve down until its loudest band sits at
        // +6, so the result is +6, not +10. This used to assert +4 because the 80 Hz band had its
        // own 4 dB ceiling; that ceiling is 8 dB now, so the preamp is what does the work here.
        float[] out = AuroraEngine.buildGains(
                config(new float[]{10, 8, 2, -3, -9, -11}, 100), speaker(), 100);

        assertEquals("the preamp must pull the loudest band down to +6", 6f, out[0], 0.01f);
        for (float gain : out) {
            assertTrue("no band may sit above the preamp ceiling", gain <= 6.01f);
        }

        // Shifting the whole curve is what keeps the preset's shape: the gaps between bands are the
        // same as in the source curve. Anything that normalised per band would break this.
        assertEquals("80 Hz vs 500 Hz gap must survive the shift", -8f, out[2] - out[0], 0.01f);
        assertEquals("80 Hz vs 200 Hz gap must survive the shift", -2f, out[1] - out[0], 0.01f);

        assertEquals("the quietest band still lands on the floor limit", -12f, out[5], 0.01f);
    }

    @Test
    public void quietVolumeLiftsTheBottomBand() {
        float[] loud = AuroraEngine.buildGains(
                config(new float[]{0, 0, 0, 0, 0, 0}, 100), speaker(), 100);
        float[] quiet = AuroraEngine.buildGains(
                config(new float[]{0, 0, 0, 0, 0, 0}, 100), speaker(), 30);
        assertTrue("80 Hz must be lifted at low volume", quiet[0] > loud[0] + 1f);
        // The ISO 226 contour also tilts the mid a little, but far less than the bass: the
        // compensation must stay a bass-weighted tilt, not a uniform lift.
        assertTrue("the mid gets less compensation than the bass", quiet[2] - loud[2] < quiet[0] - loud[0]);
        assertTrue("the mid stays close to flat", Math.abs(quiet[2] - loud[2]) <= 2.5f);
    }

    @Test
    public void bassTightensWithVolume() {
        AudioEq.MbcPlan quiet = AuroraEngine.buildMbcPlan(20);
        AudioEq.MbcPlan loud = AuroraEngine.buildMbcPlan(100);
        assertTrue("the bass band compresses harder at high volume", loud.ratio[0] > quiet.ratio[0]);
    }

    @Test
    public void onlyTheBassBandCompresses() {
        // Glue compression across the whole spectrum flattened every preset into the same denser
        // sound, so bands 1-5 must stay at ratio 1 at every volume.
        for (int volume : new int[]{0, 40, 70, 100}) {
            AudioEq.MbcPlan plan = AuroraEngine.buildMbcPlan(volume);
            for (int i = 1; i < AudioEq.MbcPlan.BANDS; i++) {
                assertEquals("band " + i + " must be transparent at volume " + volume,
                        1f, plan.ratio[i], 0.0001f);
            }
            assertTrue("the bass band always compresses at least a little",
                    plan.ratio[0] >= 1.8f && plan.ratio[0] <= 3.0f);
        }
    }
}
