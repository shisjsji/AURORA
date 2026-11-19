package com.aurora.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Tests for the spatializer headroom rule.
 *
 * <p>The renderer's makeup gain sits downstream of the limiter, so a spatialized session must leave
 * with a deeper ceiling. These assertions guard the two ways that can break: the depth going
 * negative (never deepened) or past the floor (silencing the session).
 */
public class LimiterTest {

    @Test
    public void plainSessionsKeepThePakShape() {
        AuroraEngine.Config config = new AuroraEngine.Config();
        assertSame("a normal session must get the exact config, untouched",
                config.limiter, AuroraEngine.limiterFor(config, false));
    }

    @Test
    public void spatialSessionsGetDeeperThreshold() {
        AuroraEngine.Config config = new AuroraEngine.Config();
        AudioEq.LimiterConfig spatial = AuroraEngine.limiterFor(config, true);
        assertEquals("default -6 dBFS deepens by the 4 dB headroom",
                -10f, spatial.thresholdDb, 0.001f);
        assertEquals("ratio unchanged", config.limiter.ratio, spatial.ratio, 0.001f);
        assertEquals("attack unchanged", config.limiter.attackMs, spatial.attackMs, 0.001f);
        assertEquals("release unchanged", config.limiter.releaseMs, spatial.releaseMs, 0.001f);
    }

    @Test
    public void deepeningStopsAtTheFloor() {
        AuroraEngine.Config config = new AuroraEngine.Config();
        config.limiter = new AudioEq.LimiterConfig(-22f, 6f, 5f, 80f);
        assertEquals("a deep pack threshold clamps at -24 dBFS, not past it",
                -24f, AuroraEngine.limiterFor(config, true).thresholdDb, 0.001f);
    }

    @Test
    public void makeupTightensTheCeiling() {
        AuroraEngine.Config config = new AuroraEngine.Config();
        config.loudnessMb = 800;   // +8 dB: 3 dB past the free 5 dB
        assertEquals("each dB of makeup past +5 takes a dB of ceiling",
                -9f, AuroraEngine.limiterFor(config, false).thresholdDb, 0.001f);
        config.loudnessMb = 0;
        config.lmh = 100;
        // Maximisation adds +6 dB of makeup (1 dB past the free 5) and its own 3 dB of linkage.
        assertEquals("full maximisation tightens by 4 dB in total",
                -10f, AuroraEngine.limiterFor(config, false).thresholdDb, 0.001f);
    }

    @Test
    public void makeupSumsUserSettingAndMaximisation() {
        AuroraEngine.Config config = new AuroraEngine.Config();
        config.loudnessMb = 300;
        config.lmh = 100;
        assertEquals("300 mB user + 600 mB maximisation", 900,
                AuroraEngine.effectiveLoudnessMb(config));
        config.loudnessMb = 800;
        assertEquals("the total is clamped at +10 dB", 1000,
                AuroraEngine.effectiveLoudnessMb(config));
    }
}