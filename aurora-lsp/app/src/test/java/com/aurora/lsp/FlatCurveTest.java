package com.aurora.lsp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The publish guard that runs before the request is sent.
 *
 * <p>A curve within half a dB of flat is the factory curve: publishing it puts a straight line in
 * the feed. The boundary has to match the server's rule exactly, or the app offers a publish button
 * that the server then refuses.
 */
public class FlatCurveTest {

    @Test
    public void flatCurvesAreRejected() {
        assertTrue("all zero", CommunitySheetsKt.isFlatCurve(new float[]{0, 0, 0, 0, 0, 0}));
        assertTrue("within half a dB both ways",
                CommunitySheetsKt.isFlatCurve(new float[]{0.4f, -0.4f, 0.2f, 0f, -0.3f, 0.1f}));
        assertTrue("exactly half a dB still counts as flat",
                CommunitySheetsKt.isFlatCurve(new float[]{0.5f, 0f, 0f, 0f, 0f, -0.5f}));
    }

    @Test
    public void curvesWithRealShapingAreAllowed() {
        assertFalse("one band past the threshold is enough",
                CommunitySheetsKt.isFlatCurve(new float[]{0, 0, 0, 0, 0, 0.6f}));
        assertFalse("a shaped curve",
                CommunitySheetsKt.isFlatCurve(new float[]{10f, 8f, 2f, -3f, -9f, -11f}));
    }
}