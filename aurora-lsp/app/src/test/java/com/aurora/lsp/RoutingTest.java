package com.aurora.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the per-route choices: widening, low-volume lift and the 200 Hz ceiling.
 *
 * <p>The same curve should not be handed to a phone speaker and to a pair of earbuds unchanged.
 * Speakers get the full widening and the full low-volume lift because both exist to compensate for
 * a small driver a few centimetres away; isolated channels get 60% of each and a higher 200 Hz
 * ceiling, because there the same amount reads as phasey and boomy rather than wide and full.
 */
public class RoutingTest {

    private static final int HEADSET = android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
    private static final int BLUETOOTH = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;

    private static AuroraEngine.Config config() {
        AuroraEngine.Config c = new AuroraEngine.Config();
        c.enabled = true;
        c.preset = "flat";
        c.strength = 100;
        return c;
    }

    private static AuroraEngine.SessionInfo route(int deviceType) {
        return new AuroraEngine.SessionInfo(1, 10001, deviceType, null);
    }

    @Test
    public void aSpeakerGetsTheWholeSpread() {
        AuroraEngine.Config c = config();
        c.spread = 5f;
        float[] out = AuroraEngine.buildSpread(c, new AuroraEngine.SessionInfo(1, -1, -1, null));
        assertEquals("the top band gets the full amount", 5f, out[5], 0.01f);
        assertEquals("1.5 kHz gets half", 2.5f, out[3], 0.01f);
        assertEquals("bass stays centred", 0f, out[0], 0.001f);
    }

    @Test
    public void isolatedChannelsGetLessSpread() {
        AuroraEngine.Config c = config();
        c.spread = 5f;
        float speaker = AuroraEngine.buildSpread(c, new AuroraEngine.SessionInfo(1, -1, -1, null))[5];
        float headset = AuroraEngine.buildSpread(c, route(HEADSET))[5];
        float bluetooth = AuroraEngine.buildSpread(c, route(BLUETOOTH))[5];
        assertEquals("wired gets 60%", speaker * 0.6f, headset, 0.01f);
        assertEquals("bluetooth gets 60%", speaker * 0.6f, bluetooth, 0.01f);
    }

    @Test
    public void noSpreadStillMeansNoWrite() {
        AuroraEngine.Config c = config();
        c.spread = 0f;
        for (int type : new int[]{-1, HEADSET, BLUETOOTH}) {
            assertTrue("type " + type + " with spread off must not widen",
                    AuroraEngine.buildSpread(c, route(type)) == null);
        }
    }

    @Test
    public void theQuietLiftIsSmallerOnIsolatedChannels() {
        AuroraEngine.Config c = config();
        // A 20 Hz-only curve makes the low-volume lift the only thing on the bottom band.
        c.userCurve = new float[AudioEq.BANDS];
        // 4 kHz carries a non-zero shape coefficient and gets no route trim, so the two scales can
        // be compared directly: the only difference between these numbers is the lift.
        float speaker = AuroraEngine.buildGains(c, new AuroraEngine.SessionInfo(1, -1, -1, null), 0)[4];
        float headset = AuroraEngine.buildGains(c, route(HEADSET), 0)[4];
        assertTrue("the lift must exist on speakers", speaker > 0.5f);
        assertEquals("wired gets 60% of the lift", speaker * 0.6f, headset, 0.01f);
    }

    @Test
    public void wiredKeepsTheBassTrimItAlwaysHad() {
        AuroraEngine.Config c = config();
        c.userCurve = new float[AudioEq.BANDS];
        float speaker80 = AuroraEngine.buildGains(c, new AuroraEngine.SessionInfo(1, -1, -1, null), 100)[0];
        float wired80 = AuroraEngine.buildGains(c, route(HEADSET), 100)[0];
        assertEquals("the wired bass trim is unchanged", speaker80 - 3f, wired80, 0.01f);
    }

    @Test
    public void aHeadphoneMayUseMoreAtTwoHundred() {
        AuroraEngine.Config c = config();
        c.userCurve = new float[]{0f, 8f, 0f, 0f, 0f, 0f};
        float speaker = AuroraEngine.buildGains(c, new AuroraEngine.SessionInfo(1, -1, -1, null), 100)[1];
        float headset = AuroraEngine.buildGains(c, route(HEADSET), 100)[1];
        assertEquals("a phone speaker stops at 4 dB", 4f, speaker, 0.01f);
        assertEquals("a headphone may reach 6 dB", 6f, headset, 0.01f);
    }
}
