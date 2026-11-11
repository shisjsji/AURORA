package com.aurora.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for the session expiry rule.
 *
 * <p>This is the one rule whose failure is audible in the other direction: releasing too early cuts
 * the EQ out mid-song, releasing too late leaves an effect bound to a session the app already
 * closed. The boundary is where the interesting cases are.
 */
public class StaleSessionTest {

    private static final long TTL = 10_000L;

    private static Map<Integer, Long> seen(Object... pairs) {
        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Integer) pairs[i], ((Number) pairs[i + 1]).longValue());
        }
        return map;
    }

    @Test
    public void freshSessionSurvives() {
        List<Integer> gone = AuroraEngine.staleSessions(seen(7, 1000L), 2000L, TTL);
        assertTrue(gone.isEmpty());
    }

    @Test
    public void sessionOnTheBoundarySurvives() {
        // Exactly TTL old: the playback callback fires at settle time and the tick polls every 2 s,
        // so a session touched right on the edge is still in use.
        List<Integer> gone = AuroraEngine.staleSessions(seen(7, 0L), TTL, TTL);
        assertTrue("a session exactly TTL old must not be released", gone.isEmpty());
    }

    @Test
    public void sessionOneMillisecondPastTheTtlIsReleased() {
        List<Integer> gone = AuroraEngine.staleSessions(seen(7, 0L), TTL + 1, TTL);
        assertEquals(1, gone.size());
        assertEquals(Integer.valueOf(7), gone.get(0));
    }

    @Test
    public void onlyTheStaleOnesGo() {
        List<Integer> gone = AuroraEngine.staleSessions(
                seen(1, 0L, 2, 19_000L, 3, 19_500L), 20_000L, TTL);
        assertEquals("only session 1 has been gone for longer than the TTL", 1, gone.size());
        assertEquals(Integer.valueOf(1), gone.get(0));
    }

    @Test
    public void emptyTableReleasesNothing() {
        List<Integer> gone = AuroraEngine.staleSessions(new HashMap<>(), 99_999L, TTL);
        assertTrue(gone.isEmpty());
    }

    @Test
    public void everyStaleSessionIsListed() {
        List<Integer> gone = new ArrayList<>(AuroraEngine.staleSessions(
                seen(1, 0L, 2, 0L, 3, 100L), 50_000L, TTL));
        assertEquals(3, gone.size());
    }
}
