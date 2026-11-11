package com.aurora.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the per-session curve: preset resolution, strength, volume compensation, route trim and
 * the boost ceilings, in the order the engine applies them.
 *
 * <p>CurveTest covers the shape of a single curve; this one covers the inputs that decide which
 * curve a session gets and how the surroundings reshape it. Each assertion names the behaviour a
 * regression would break, so a failure points at a line rather than at "the maths".
 */
public class GainsTest {

    private static AuroraEngine.Config config() {
        AuroraEngine.Config c = new AuroraEngine.Config();
        c.enabled = true;
        c.preset = "flat";
        c.strength = 100;
        return c;
    }

    private static AuroraEngine.SessionInfo speaker() {
        return new AuroraEngine.SessionInfo(1, -1, -1, null);
    }

    private static AuroraEngine.SessionInfo route(int deviceType, String pkg) {
        return new AuroraEngine.SessionInfo(1, 10001, deviceType, pkg);
    }

    @Test
    public void appAssignmentWinsOverTheGlobalPreset() {
        AuroraEngine.Config c = config();
        c.userCurve = new float[]{6, 6, 6, 6, 6, 6};
        c.userPresets.put("u1", new float[]{-6, -6, -6, -6, -6, -6});
        c.appMap.put("com.example.player", "u1");

        float[] out = AuroraEngine.buildGains(c, route(-1, "com.example.player"), 100);
        for (float gain : out) {
            assertEquals("the app's own curve must replace the global one", -6f, gain, 0.1f);
        }
    }

    @Test
    public void assignmentToAMissingPresetFallsBackToTheGlobalCurve() {
        AuroraEngine.Config c = config();
        c.userCurve = new float[]{4, 4, 4, 4, 4, 4};
        c.appMap.put("com.example.player", "u-deleted");

        float[] out = AuroraEngine.buildGains(c, route(-1, "com.example.player"), 100);
        for (float gain : out) {
            assertEquals("a deleted preset must not silence the app", 4f, gain, 0.1f);
        }
    }

    @Test
    public void assignmentToABuiltInPresetUsesThatPreset() {
        AuroraEngine.Config c = config();
        c.userCurve = new float[]{0, 0, 0, 0, 0, 0};
        String builtIn = Presets.KEYS[1];
        c.appMap.put("com.example.player", builtIn);

        float[] out = AuroraEngine.buildGains(c, route(-1, "com.example.player"), 100);
        float[] expected = AuroraEngine.buildGains(
                withCurve(Presets.curve(1)), speaker(), 100);
        for (int i = 0; i < out.length; i++) {
            assertEquals("band " + i + " must follow the assigned preset",
                    expected[i], out[i], 0.05f);
        }
    }

    private static AuroraEngine.Config withCurve(float[] curve) {
        AuroraEngine.Config c = config();
        c.userCurve = curve;
        return c;
    }

    @Test
    public void strengthScalesTheCurve() {
        AuroraEngine.Config full = withCurve(new float[]{4, 3, 2, 1, 0, -1});
        AuroraEngine.Config half = withCurve(new float[]{4, 3, 2, 1, 0, -1});
        half.strength = 50;

        float[] a = AuroraEngine.buildGains(full, speaker(), 100);
        float[] b = AuroraEngine.buildGains(half, speaker(), 100);
        for (int i = 0; i < a.length; i++) {
            assertEquals("band " + i + " must halve with the strength slider",
                    a[i] / 2f, b[i], 0.05f);
        }
    }

    @Test
    public void zeroStrengthProducesAFlatCurve() {
        AuroraEngine.Config c = withCurve(new float[]{8, 6, 3, -2, -6, -9});
        c.strength = 0;
        for (float gain : AuroraEngine.buildGains(c, speaker(), 100)) {
            assertEquals("strength 0 must be genuinely flat, not merely quiet",
                    0f, gain, 0.01f);
        }
    }

    @Test
    public void quietPlaybackLiftsTheBottomBands() {
        AuroraEngine.Config c = withCurve(new float[]{-6, -6, -6, -6, -6, -6});
        float[] loud = AuroraEngine.buildGains(c, speaker(), 100);
        float[] quiet = AuroraEngine.buildGains(c, speaker(), 20);

        assertTrue("80 Hz must be lifted at low volume", quiet[0] > loud[0]);
        assertTrue("the lift must fade out toward the top",
                quiet[0] - loud[0] > quiet[5] - loud[5]);
    }

    @Test
    public void loudPlaybackGetsNoCompensation() {
        AuroraEngine.Config c = withCurve(new float[]{-6, -6, -6, -6, -6, -6});
        float[] atFull = AuroraEngine.buildGains(c, speaker(), 100);
        float[] atSixty = AuroraEngine.buildGains(c, speaker(), 60);
        for (int i = 0; i < atFull.length; i++) {
            assertEquals("60% is the threshold, not the start of the lift",
                    atFull[i], atSixty[i], 0.01f);
        }
    }

    @Test
    public void wiredHeadphonesTrimTheBass() {
        AuroraEngine.Config c = withCurve(new float[]{0, 0, 0, 0, 0, 0});
        float[] speaker = AuroraEngine.buildGains(c, speaker(), 100);
        float[] wired = AuroraEngine.buildGains(
                c, route(android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, null), 100);

        assertEquals("80 Hz drops 3 dB on a wired route", speaker[0] - 3f, wired[0], 0.01f);
        assertEquals("200 Hz drops 1 dB on a wired route", speaker[1] - 1f, wired[1], 0.01f);
        assertEquals("500 Hz is untouched by the route trim", speaker[2], wired[2], 0.01f);
    }

    @Test
    public void bluetoothTrimsOnlyTheBass() {
        AuroraEngine.Config c = withCurve(new float[]{0, 0, 0, 0, 0, 0});
        float[] speaker = AuroraEngine.buildGains(c, speaker(), 100);
        float[] bt = AuroraEngine.buildGains(
                c, route(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, null), 100);

        assertEquals("80 Hz drops 2 dB over A2DP", speaker[0] - 2f, bt[0], 0.01f);
        assertEquals("200 Hz is untouched over A2DP", speaker[1], bt[1], 0.01f);
    }

    @Test
    public void boostCeilingIsPerBand() {
        // 200 Hz is the one band whose ceiling sits below the preamp limit, so it is the only one
        // a test can observe. The 8 dB ceilings on 80 Hz / 1.5 k / 4 k / 10 k cannot bind: the +6
        // dB preamp has already pulled those bands down to 6 before the cap is consulted.
        AuroraEngine.Config muddy = config();
        muddy.userCurve = new float[]{0, 6, 0, 0, 0, 0};
        assertEquals("the 200 Hz ceiling is 4 dB", 4f,
                AuroraEngine.buildGains(muddy, speaker(), 100)[1], 0.01f);
    }

    @Test
    public void preampHoldsTheLoudestBandAtSix() {
        // A single band asking for +11 leaves the preamp 5 dB of headroom to give back; the result
        // is 6, not 11 and not the band's own 8 dB ceiling.
        AuroraEngine.Config c = config();
        c.userCurve = new float[]{0, 0, 0, 11, 0, 0};
        assertEquals("the preamp, not the band ceiling, is what binds here", 6f,
                AuroraEngine.buildGains(c, speaker(), 100)[3], 0.01f);
    }

    @Test
    public void packCeilingsReplaceTheDefaults() {
        AuroraEngine.Config c = config();
        c.userCurve = new float[]{0, 0, 9, 9, 9, 0};
        c.maxBoost = new float[]{8, 8, 2, 2, 2, 8};

        float[] out = AuroraEngine.buildGains(c, speaker(), 100);
        assertEquals("a pack that allows only 2 dB in the mids must be obeyed",
                2f, out[2], 0.01f);
        assertEquals("and the same ceiling applies to 1.5 kHz", 2f, out[3], 0.01f);
    }

    @Test
    public void shortCeilingTableIsExtendedByItsLastValue() {
        // A pack is validated to six entries, but the defensive read must not throw if it is not.
        AuroraEngine.Config c = config();
        c.userCurve = new float[]{0, 0, 5, 0, 0, 5};
        c.maxBoost = new float[]{8, 8, 3};

        float[] out = AuroraEngine.buildGains(c, speaker(), 100);
        assertEquals("the last entry covers the remaining bands", 3f, out[2], 0.01f);
        assertEquals("and reaches the top band too", 3f, out[5], 0.01f);
    }

    @Test
    public void cutsAreNotLimitedByTheBoostCeiling() {
        AuroraEngine.Config c = config();
        c.userCurve = new float[]{-12, -12, -12, -12, -12, -12};

        float[] out = AuroraEngine.buildGains(c, speaker(), 100);
        for (float gain : out) {
            assertEquals("a ceiling must not turn a cut into a boost", -12f, gain, 0.01f);
        }
    }

    @Test
    public void unknownPresetFallsBackToFlat() {
        AuroraEngine.Config c = config();
        c.preset = "no-such-preset";
        c.userCurve = null;

        float[] out = AuroraEngine.buildGains(c, speaker(), 100);
        assertNotNull(out);
        for (float gain : out) {
            assertEquals("an unknown preset must resolve to flat, not throw", 0f, gain, 0.01f);
        }
    }
}
