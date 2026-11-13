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
}