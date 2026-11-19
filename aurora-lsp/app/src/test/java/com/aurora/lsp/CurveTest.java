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
    public void dialogFocusTiltsTheMidWithoutTouchingThePreset() {
        AuroraEngine.Config plain = config(new float[]{0, 0, 0, 0, 0, 0}, 100);
        AuroraEngine.Config focused = config(new float[]{0, 0, 0, 0, 0, 0}, 100);
        focused.dialog = true;
        float[] a = AuroraEngine.buildGains(plain, speaker(), 100);
        float[] b = AuroraEngine.buildGains(focused, speaker(), 100);
        assertTrue("the 200 Hz band must come down", b[1] < a[1] - 1f);
        assertTrue("the 1.5 kHz band must come up", b[3] > a[3] + 1f);
        assertTrue("a flat preset is not changed on disk - only the output tilts", Math.abs(a[1]) < 0.01f);
    }

    @Test
    public void maximisationDensityRaisesMbcRatios() {
        AuroraEngine.Config plain = config(new float[]{0, 0, 0, 0, 0, 0}, 100);
        AuroraEngine.Config loud = config(new float[]{0, 0, 0, 0, 0, 0}, 100);
        loud.lmh = 100;
        AudioEq.MbcPlan a = AuroraEngine.buildMbcPlan(plain, 100);
        AudioEq.MbcPlan b = AuroraEngine.buildMbcPlan(loud, 100);
        assertTrue("maximisation must compress harder", b.ratio[3] > a.ratio[3]);
        assertTrue("maximisation moves thresholds earlier", b.thresholdDb[3] < a.thresholdDb[3]);
        for (int i = 0; i < AudioEq.MbcPlan.BANDS; i++) {
            assertTrue("ratio stays inside its bounds", b.ratio[i] >= 1f && b.ratio[i] <= 6f);
            assertTrue("threshold stays inside its bounds",
                    b.thresholdDb[i] >= -60f && b.thresholdDb[i] <= -6f);
        }
    }

    @Test
    public void bassTightensWithVolume() {
        AuroraEngine.Config plain = config(new float[]{0, 0, 0, 0, 0, 0}, 100);
        AudioEq.MbcPlan quiet = AuroraEngine.buildMbcPlan(plain, 20);
        AudioEq.MbcPlan loud = AuroraEngine.buildMbcPlan(plain, 100);
        assertTrue("the bass band compresses harder at high volume", loud.ratio[0] > quiet.ratio[0]);
    }

    @Test
    public void dialogPlanDensifiesThePresenceBands() {
        AuroraEngine.Config plain = config(new float[]{0, 0, 0, 0, 0, 0}, 100);
        AuroraEngine.Config dialog = config(new float[]{0, 0, 0, 0, 0, 0}, 100);
        dialog.dialog = true;
        AudioEq.MbcPlan a = AuroraEngine.buildMbcPlan(plain, 100);
        AudioEq.MbcPlan b = AuroraEngine.buildMbcPlan(dialog, 100);
        assertTrue("the dialog bands compress harder", b.ratio[3] > a.ratio[3]);
        assertEquals("the bass band is untouched by dialog focus", a.ratio[0], b.ratio[0], 0.001f);
    }
}
