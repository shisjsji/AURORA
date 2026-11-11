package com.aurora.lsp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Multi-band equalizer bound to one playback session.
 *
 * <p>Why not the Dolby DAP: measured on this firmware, the {@code dap_hw} effect accepts vendor
 * parameters but does not apply them to audio that goes through the spatializer output thread
 * (which is what streaming players use). {@link android.media.audiofx.DynamicsProcessing} is a
 * software stage that really processes the PCM in the mix chain, and it works both on the normal
 * path and on the spatializer path.
 *
 * <p>The pre-EQ stage is used so the six bands can be placed wherever the speaker can actually
 * reproduce sound.
 */
public final class AudioEq {

    /** Band centres in Hz: low weight, warmth, boxiness, voice, clarity, air. */
    public static final float[] BAND_HZ = {80f, 200f, 500f, 1500f, 4000f, 10000f};
    public static final int BANDS = BAND_HZ.length;
    public static final float MAX_DB = 12f;

    /** Read-back tolerance: an OEM build that half-applies a write lands far outside this. */
    static final float VERIFY_TOLERANCE_DB = 0.6f;

    /** Custom architecture: 0 = {@code VARIANT_FAVOR_FREQUENCY_RESOLUTION}. */
    static final int ARCH_VARIANT = 0;
    /** MBC band count; only band 0 is written, the rest stay at ratio 1 (no compression). */
    static final int MBC_BANDS = 6;
    /** Platform default pre-EQ band count, and the count the six-point curve is authored in. */
    static final int DEFAULT_BANDS = 6;
    /**
     * Band count of the precise chain. Ten cutoffs only buy anything when the frame is long
     * enough to resolve them: at 10 ms a 512-point transform is 93.75 Hz wide, which merges the
     * two lowest of ten bands into one bin and leaves the second band writing nothing at all.
     */
    static final int PRECISE_BANDS = 10;
    /** Frame length that makes ten bands real: 1024 points, 46.9 Hz per bin, +10 ms latency. */
    static final float PRECISE_FRAME_MS = 20f;

    private static Constructor<?> dpCtor;
    private static Constructor<?> dpConfigCtor;
    private static Constructor<?> configBuilderCtor;
    private static Method builderSetFrame;
    private static Method builderBuild;
    private static Constructor<?> bandCtor;
    private static Constructor<?> limiterCtor;
    private static Constructor<?> mbcBandCtor;
    private static Method setBandAll;
    private static Method setBandByChannel;
    private static Method getPreEqBand;
    private static Method bandGetGain;
    private static Method setMbcBandAll;
    private static Method setLimiterAll;
    private static Method setEnabled;
    private static Method hasControl;
    private static Method release;
    private static String resolveError;

    // LoudnessEnhancer
    private static Constructor<?> leCtor;
    private static Method leSetTargetGain;
    private static Method leSetEnabled;
    private static Method leRelease;

    private Object effect;
    private Object loudnessEnhancer;
    private int bandCount;
    /** Architecture this effect was asked for; 0 means the platform default. */
    private int wantedBands;
    private float wantedFrameMs;
    private String lastError = "";
    private String signature = "";
    private boolean applied;
    /**
     * What this chain really has, one entry per stage: "bands=6 limiter=ok mbc=ok loudness=ok".
     *
     * <p>Every one of those writes used to be a silent catch: a build where the limiter lookup
     * failed looked exactly like one where it worked, and the device quietly ran AOSP's default
     * -2 dBFS 10:1 brickwall. Recording the outcome turns "sounds wrong" into a value that the
     * diagnostics page and the device packs can act on.
     */
    private final Map<String, String> stageMap = new LinkedHashMap<>();
    private float limiterThreshold = -3f;
    private float limiterRatio = 10f;
    private float limiterAttackMs = 5f;
    private float limiterReleaseMs = 60f;

    /** Limiter shape for this device; comes from the device pack, or the code default. */
    public static final class LimiterConfig {
        public final float thresholdDb;
        public final float ratio;
        public final float attackMs;
        public final float releaseMs;

        public LimiterConfig(float thresholdDb, float ratio, float attackMs, float releaseMs) {
            this.thresholdDb = thresholdDb;
            this.ratio = ratio;
            this.attackMs = attackMs;
            this.releaseMs = releaseMs;
        }
    }

    private static synchronized boolean resolve() {
        if (dpCtor != null) return true;
        if (resolveError != null) return false;
        try {
            Class<?> dp = Class.forName("android.media.audiofx.DynamicsProcessing");
            Class<?> band = Class.forName("android.media.audiofx.DynamicsProcessing$EqBand");
            dpCtor = dp.getConstructor(int.class, int.class);
            // The custom architecture path: a Config built from scratch instead of AOSP's fixed
            // six-band, 10 ms default. Optional - a build without it falls back to the 2-arg
            // constructor and the stock architecture.
            try {
                Class<?> config = Class.forName("android.media.audiofx.DynamicsProcessing$Config");
                Class<?> builder = Class.forName(
                        "android.media.audiofx.DynamicsProcessing$Config$Builder");
                configBuilderCtor = builder.getConstructor(int.class, int.class, boolean.class,
                        int.class, boolean.class, int.class, boolean.class, int.class,
                        boolean.class);
                builderSetFrame = builder.getMethod("setPreferredFrameDuration", float.class);
                builderBuild = builder.getMethod("build");
                dpConfigCtor = dp.getConstructor(int.class, int.class, config);
            } catch (Throwable ignored) {
            }
            bandCtor = band.getConstructor(boolean.class, float.class, float.class);
            setBandAll = dp.getMethod("setPreEqBandAllChannelsTo", int.class, band);
            setBandByChannel = dp.getMethod("setPreEqBandByChannelIndex", int.class, int.class, band);
            setEnabled = dp.getMethod("setEnabled", boolean.class);
            hasControl = dp.getMethod("hasControl");
            release = dp.getMethod("release");
            try {
                getPreEqBand = dp.getMethod("getPreEqBandByChannelIndex", int.class, int.class);
                bandGetGain = band.getMethod("getGain");
            } catch (Throwable ignored) {
            }
            try {
                // Limiter(inUse, enabled, linkGroup, attack, release, ratio, threshold, postGain).
                // The constructor lookup used to ask for (boolean, int, float x5), which matches
                // nothing: the lookup threw, the catch swallowed it, and the limiter silently never
                // ran. The engine then processed audio with AOSP defaults - a -2 dBFS, 10:1, 1 ms
                // brickwall - on every device, including devices whose EQ writes had failed.
                Class<?> limiter = Class.forName("android.media.audiofx.DynamicsProcessing$Limiter");
                limiterCtor = limiter.getConstructor(boolean.class, boolean.class, int.class,
                        float.class, float.class, float.class, float.class, float.class);
                setLimiterAll = dp.getMethod("setLimiterAllChannelsTo", limiter);
            } catch (Throwable ignored) {
            }
            try {
                Class<?> mbcBand = Class.forName("android.media.audiofx.DynamicsProcessing$MbcBand");
                mbcBandCtor = mbcBand.getConstructor(boolean.class, float.class, float.class,
                        float.class, float.class, float.class, float.class, float.class,
                        float.class, float.class, float.class);
                setMbcBandAll = dp.getMethod("setMbcBandAllChannelsTo", int.class, mbcBand);
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable error) {
            resolveError = describe(error);
            return false;
        }
    }

    private static synchronized boolean resolveLoudness() {
        if (leCtor != null) return true;
        try {
            Class<?> le = Class.forName("android.media.audiofx.LoudnessEnhancer");
            leCtor = le.getConstructor(int.class, int.class);
            leSetTargetGain = le.getMethod("setTargetGain", int.class);
            leSetEnabled = le.getMethod("setEnabled", boolean.class);
            leRelease = le.getMethod("release");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public synchronized boolean open(int session, LimiterConfig limiter) {
        return open(session, limiter, DEFAULT_BANDS, 0f);
    }

    /**
     * Opens the effect on a chain we describe ourselves.
     *
     * <p>The two-argument {@code DynamicsProcessing} constructor leaves the platform to pick the
     * architecture: six pre-EQ bands and a 10 ms frame, on every device. Six bands spread over
     * 220 Hz-20 kHz put 80 Hz and 200 Hz one bin apart at that frame length, so the low end is
     * effectively one band no matter what the curve says. Passing our own {@code Config} is the
     * only way to raise the band count, because the native engine fixes it in
     * {@code DP_PARAM_ENGINE_ARCHITECTURE} at construction and ignores later changes.
     *
     * @param preEqBands pre-EQ band count to request; the platform default is used when this is
     *                   not above {@link #DEFAULT_BANDS} or the reflection path is unavailable.
     * @param frameMs    preferred frame duration in ms; 0 keeps the platform default. A longer
     *                   frame means a larger FFT: 20 ms at 48 kHz is 1024 points (46.9 Hz bins)
     *                   against 512 points (93.75 Hz) at 10 ms, at the cost of 10 ms of latency.
     */
    public synchronized boolean open(int session, LimiterConfig limiter, int preEqBands,
                                     float frameMs) {
        close();
        stageMap.clear();
        if (!resolve()) {
            lastError = resolveError;
            stage("chain", "unavailable");
            return false;
        }
        if (session <= 0) {
            lastError = "invalid session " + session;
            stage("chain", "bad_session");
            return false;
        }
        if (limiter != null) {
            limiterThreshold = limiter.thresholdDb;
            limiterRatio = limiter.ratio;
            limiterAttackMs = limiter.attackMs;
            limiterReleaseMs = limiter.releaseMs;
        }
        try {
            effect = createEffect(session, preEqBands, frameMs);
            bandCount = readBandCount();
            if (wantedBands > DEFAULT_BANDS && bandCount < wantedBands) {
                // The native engine keeps its own band count when it dislikes ours: it logs a
                // warning and carries on with six. Rather than write a ten-band curve into six
                // bands and report "6/10" forever, give up the extra bands here and reopen on the
                // platform default - the curve the user drew still lands, just at the old
                // resolution, and the reason is recorded.
                stage("config", "kept:" + bandCount);
                close();
                effect = createEffect(session, DEFAULT_BANDS, 0f);
                bandCount = readBandCount();
            }
            if (!Boolean.TRUE.equals(hasControl.invoke(effect))) {
                lastError = "no control over session " + session;
                stage("control", "denied");
                // Without this the effect stayed bound to the session for its whole life: it was
                // never added to the engine's table, so releaseStale/releaseAll could never reach
                // it, and a half-configured chain (AOSP's default limiter, no curve) kept
                // processing. That is the "writes failed but it still sounds broken" report.
                close();
                return false;
            }
            // The requested architecture, then what the chain actually reports. If the effect came
            // up with fewer bands than asked for, the second number is the one that is real.
            stage("arch", wantedBands <= 0 ? "stock"
                    : wantedBands + "x" + Math.round(wantedFrameMs) + "ms");
            stage("bands", bandCount + "/" + Math.max(BANDS, wantedBands));
            // The effect comes up enabled with AOSP default stages. Take it offline until the
            // curve and limiter land, so a device whose writes will fail never runs the default
            // -2 dBFS 10:1 brickwall on its own.
            try {
                setEnabled.invoke(effect, false);
            } catch (Throwable ignored) {
            }
            enableLimiter();
            enableBaseCompression();
            openLoudness(session);
            if (bandCount <= 0) {
                // open() used to report success up here only to return false for this case, which
                // left the effect bound and the caller unable to tell why nothing was written.
                lastError = "session " + session + " reports no pre-EQ bands";
                close();
                return false;
            }
            return true;
        } catch (Throwable error) {
            lastError = describe(error);
            close();
            return false;
        }
    }

    /** Frame duration the platform uses when we do not ask for one. */
    static final float PLATFORM_FRAME_MS = 10f;

    private int readBandCount() throws Throwable {
        Object preEq = effect.getClass()
                .getMethod("getPreEqByChannelIndex", int.class).invoke(effect, 0);
        return (Integer) preEq.getClass().getMethod("getBandCount").invoke(preEq);
    }

    /**
     * Builds the effect, on our own architecture when one was asked for.
     *
     * <p>Falls back to the two-argument constructor, which lets the platform choose six bands and
     * a 10 ms frame, whenever the custom chain is not requested or this build refuses it. A
     * device that rejects the architecture still gets a curve - the same trade the whole class
     * makes everywhere else.
     */
    private Object createEffect(int session, int preEqBands, float frameMs) throws Throwable {
        boolean custom = preEqBands > DEFAULT_BANDS || frameMs > 0f;
        if (!custom) {
            wantedBands = 0;
            wantedFrameMs = 0f;
            return dpCtor.newInstance(0, session);
        }
        if (configBuilderCtor == null || dpConfigCtor == null
                || builderBuild == null || builderSetFrame == null) {
            wantedBands = 0;
            wantedFrameMs = 0f;
            stage("config", "no_builder");
            return dpCtor.newInstance(0, session);
        }
        int bands = Math.max(DEFAULT_BANDS, preEqBands);
        try {
            // channelCount 1: the platform resizes the config to the real channel count itself
            // (Config(int, Config)), so there is no need to know it up front.
            Object builder = configBuilderCtor.newInstance(ARCH_VARIANT, 1,
                    true, bands, true, MBC_BANDS, false, 0, true);
            if (frameMs > 0f) builderSetFrame.invoke(builder, frameMs);
            Object config = builderBuild.invoke(builder);
            Object created = dpConfigCtor.newInstance(0, session, config);
            wantedBands = bands;
            wantedFrameMs = frameMs > 0f ? frameMs : PLATFORM_FRAME_MS;
            return created;
        } catch (Throwable error) {
            stage("config", "fallback:" + describe(error));
            wantedBands = 0;
            wantedFrameMs = 0f;
            return dpCtor.newInstance(0, session);
        }
    }

    /**
     * Whether the open chain already has the architecture the settings ask for.
     *
     * <p>The engine asks this every tick: band count and frame length are fixed when the native
     * chain is configured, so switching precision has to rebuild the effect rather than write
     * new parameters into it.
     */
    public synchronized boolean matchesArchitecture(int preEqBands, float frameMs) {
        if (effect == null) return false;
        int bands = preEqBands > DEFAULT_BANDS ? preEqBands : 0;
        if (bands <= 0 && frameMs <= 0f) return wantedBands <= 0;
        return wantedBands == bands && Math.abs(wantedFrameMs - frameMs) < 0.01f;
    }

    private void openLoudness(int session) {
        if (!resolveLoudness()) {
            stage("loudness", "skip");
            return;
        }
        try {
            loudnessEnhancer = leCtor.newInstance(0, session);
            stage("loudness", "ok");
        } catch (Throwable error) {
            loudnessEnhancer = null;
            stage("loudness", "fail:" + describe(error));
        }
    }

    /** Stage outcomes of the last open(), e.g. {@code "bands=6/6 limiter=ok mbc=ok"}. */
    public synchronized String stages() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : stageMap.entrySet()) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    private void stage(String name, String value) {
        String clean = value == null ? "" : value.replace(' ', '_').replace('\n', '_');
        if (clean.length() > 48) clean = clean.substring(0, 48);
        stageMap.put(name, clean);
    }

    /**
     * Writes the curve, optionally with per-channel spread for stereo widening.
     *
     * <p>When {@code spread} is non-zero and per-channel access works, the left channel gets
     * {@code gains + spread} and the right gets {@code gains - spread} above 500 Hz. The brain
     * reads that inter-channel difference as width. Low frequencies stay centred: separating
     * bass makes it thin rather than wide.
     *
     * @param bandHz active band table: the device pack's or the engine's fallback, not necessarily
     *               the compiled-in {@link #BAND_HZ}.
     */
    public synchronized boolean apply(float[] gains, float[] spread, int loudnessMillibels,
                                      boolean enabledStage, float[] bandHz) {
        if (effect == null || bandHz == null) return false;
        try {
            // Not capped at BANDS: the engine expands the six-point curve to however many bands
            // the chain was opened with, and a cap here would throw that away again and write the
            // six-band curve into the first six bands of a twelve-band chain.
            int wanted = gains == null ? 0 : gains.length;
            int used = Math.min(bandCount, wanted);
            if (used < wanted) {
                // The chain exposes fewer bands than the caller prepared a curve for. The surplus
                // used to be dropped without a word: the UI listed six written bands, the DSP had
                // four. Reported against the table that was actually asked for, so a device that
                // rejects our architecture reads as "6/10", not "6/6 - all fine".
                stage("short", used + "/" + wanted);
            }
            float[] written = new float[used];
            boolean perChannel = setBandByChannel != null;
            for (int i = 0; i < used; i++) {
                float gain = Math.max(-MAX_DB, Math.min(MAX_DB, gains[i]));
                written[i] = gain;
                // Catch per band, not per call. A device that rejects one particular frequency
                // reports "bad parameter value" with no hint which band caused it; recording the
                // index, centre and value turns that into something an adaptation can act on.
                try {
                    // Surplus bands are left untouched: DynamicsProcessing reports them flat by
                    // default, and parking them all on the top band stacked filters we never
                    // meant to add.
                    if (perChannel && widened(spread, i, bandHz)) {
                        float delta = Math.max(-8f, Math.min(8f, spread[i]));
                        float[] pair = channelGains(gain, delta);
                        written[i] = pair[0];
                        // Channel 0 = left, channel 1 = right
                        setBandByChannel.invoke(effect, 0, i,
                                bandCtor.newInstance(true, bandHz[i], pair[0]));
                        try {
                            setBandByChannel.invoke(effect, 1, i,
                                    bandCtor.newInstance(true, bandHz[i], pair[1]));
                        } catch (Throwable mono) {
                            // The session has no second channel. Keep the band by putting it on
                            // all channels: losing the widening is better than losing the curve,
                            // and losing the whole write is worse than both.
                            setBandAll.invoke(effect, i, bandCtor.newInstance(true, bandHz[i], gain));
                            written[i] = gain;
                        }
                    } else {
                        setBandAll.invoke(effect, i, bandCtor.newInstance(true, bandHz[i], gain));
                    }
                } catch (Throwable error) {
                    lastError = "band " + i + " @ " + Math.round(bandHz[i]) + " Hz, gain "
                            + String.format(java.util.Locale.US, "%.1f", gain) + " dB: "
                            + describe(error);
                    // A half-written effect must not keep processing: the limiter from open()
                    // would be the only stage left alive on the chain.
                    disableQuietly();
                    forgetApplied();
                    return false;
                }
            }
            try {
                setEnabled.invoke(effect, enabledStage);
            } catch (Throwable error) {
                lastError = "setEnabled(" + enabledStage + "): " + describe(error);
                forgetApplied();
                return false;
            }

            verifyBands(written, used);

            // LoudnessEnhancer: boosts perceived loudness without raising peaks.
            if (loudnessEnhancer != null && leSetTargetGain != null) {
                try {
                    leSetTargetGain.invoke(loudnessEnhancer, loudnessMillibels);
                    leSetEnabled.invoke(loudnessEnhancer, true);
                } catch (Throwable error) {
                    // Not fatal - the curve is already in - but the UI offers this as a switch,
                    // so a switch that silently does nothing is a lie worth recording.
                    stage("loudness", "fail:" + describe(error));
                }
            }

            return true;
        } catch (Throwable error) {
            lastError = describe(error);
            forgetApplied();
            return false;
        }
    }

    /**
     * Reads the written bands back off the effect.
     *
     * <p>Some OEM builds accept {@code setPreEqBand*} and keep the old value; the write succeeds,
     * nothing throws, and the phone just sounds unprocessed. Comparing what the object holds
     * against what we asked for is the only way to see that without an ear.
     */
    private void verifyBands(float[] written, int used) {
        if (used <= 0 || written == null) return;
        if (getPreEqBand == null || bandGetGain == null) return;
        float worst = 0f;
        try {
            for (int i = 0; i < used; i++) {
                Object band = getPreEqBand.invoke(effect, 0, i);
                Object value = bandGetGain.invoke(band);
                if (!(value instanceof Float)) return;
                worst = Math.max(worst, Math.abs((Float) value - written[i]));
            }
        } catch (Throwable ignored) {
            return;
        }
        stage("verify", worst <= VERIFY_TOLERANCE_DB
                ? "ok"
                : "mismatch:" + String.format(java.util.Locale.US, "%.1f", worst) + "dB");
    }

    /** A write that failed leaves the chain disabled; say so instead of claiming the curve is on. */
    private void forgetApplied() {
        applied = false;
        signature = "";
    }

    private static float clamp(float v) {
        return Math.max(-MAX_DB, Math.min(MAX_DB, v));
    }

    /** Below this the two channels get the same gain: separating bass makes it thin, not wide. */
    static final float WIDEN_FROM_HZ = 500f;

    /**
     * Whether this band is written per channel.
     *
     * <p>Split out of the reflection call so the rule itself can be tested on a plain JVM - the
     * effect object cannot be built there, but the decision it depends on can.
     */
    static boolean widened(float[] spread, int bandIndex) {
        return widened(spread, bandIndex, BAND_HZ);
    }

    /** Same rule against the active band table, which a device pack may have replaced. */
    static boolean widened(float[] spread, int bandIndex, float[] bandHz) {
        return spread != null
                && bandIndex >= 0
                && bandIndex < spread.length
                && bandHz != null
                && bandIndex < bandHz.length
                && bandHz[bandIndex] >= WIDEN_FROM_HZ;
    }

    /** Takes the effect off the chain without throwing: called from failure paths only. */
    private void disableQuietly() {
        try {
            setEnabled.invoke(effect, false);
        } catch (Throwable ignored) {
        }
    }

    /** Left and right gains for one band, clamped the same way the single-channel path is. */
    static float[] channelGains(float gain, float delta) {
        return new float[]{clamp(gain + delta), clamp(gain - delta)};
    }

    /** Lower bound used when guessing the bottom of a band's interval; nothing is cut below 40 Hz. */
    static final float EXPAND_FLOOR_HZ = 40f;

    /**
     * Widens a six-point band table to {@code bands} cutoffs on a log scale.
     *
     * <p>The table is a list of upper band edges, so the six compiled-in entries leave one huge
     * step from 80 Hz to 200 Hz and another from 200 Hz to 500 Hz. Asking the engine for more
     * bands only helps if there are more cutoffs to give it; this is where they come from.
     * Below 40 Hz nothing is added - a phone speaker reproduces none of it.
     */
    static float[] expandTable(float[] tableHz, int bands) {
        if (bands <= 0 || tableHz == null || tableHz.length == 0) return new float[0];
        if (bands == tableHz.length) return tableHz.clone();
        float top = tableHz[tableHz.length - 1];
        // The lowest cutoff is kept as authored. Reaching further down would be free of audible
        // gain - the DSP covers everything below the first cutoff anyway - while handing a device
        // that only ever saw 80 Hz a new number it may reject, which would cost the whole curve.
        float bottom = tableHz[0];
        if (!(bottom > 0f) || bottom >= top) return tableHz.clone();
        if (bands == 1) return new float[]{top};
        float[] out = new float[bands];
        double logBottom = Math.log(bottom);
        double step = (Math.log(top) - logBottom) / (bands - 1);
        for (int i = 0; i < bands; i++) {
            out[i] = i == 0 ? bottom
                    : i == bands - 1 ? top
                    : (float) Math.exp(logBottom + step * i);
        }
        return out;
    }

    /** Geometric centre of each band interval: the frequency a band's gain actually governs. */
    static float[] bandCentres(float[] tableHz) {
        if (tableHz == null) return new float[0];
        float[] out = new float[tableHz.length];
        float previous = Math.max(EXPAND_FLOOR_HZ / 2f, tableHz[0] / 2f);
        for (int i = 0; i < tableHz.length; i++) {
            out[i] = (float) Math.sqrt((double) previous * tableHz[i]);
            previous = tableHz[i];
        }
        return out;
    }

    /**
     * Resamples a curve authored on {@code tableHz} onto {@code bands} cutoffs.
     *
     * <p>The six-point curve is a staircase in the DSP (each band's gain covers its whole
     * interval), so the honest thing to interpolate is the value at each interval's geometric
     * centre, in log frequency. That keeps the shape a user drew while turning six steps into ten
     * or twelve.
     */
    static float[] expandGains(float[] gains, float[] tableHz, int bands) {
        if (gains == null || gains.length == 0) return null;
        if (tableHz == null || tableHz.length < 2) return gains.clone();
        float[] target = expandTable(tableHz, bands);
        if (target.length == 0) return gains.clone();
        if (target.length == gains.length && tableHz.length == gains.length) return gains.clone();
        float[] source = bandCentres(tableHz);
        float[] wanted = bandCentres(target);
        float[] out = new float[target.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = interpolateLog(source, gains, wanted[i]);
        }
        return out;
    }

    private static float interpolateLog(float[] centres, float[] values, float hz) {
        int last = Math.min(centres.length, values.length) - 1;
        if (last < 0) return 0f;
        if (hz <= centres[0]) return values[0];
        if (hz >= centres[last]) return values[last];
        for (int i = 0; i < last; i++) {
            if (hz <= centres[i + 1]) {
                double a = Math.log(centres[i]);
                double b = Math.log(centres[i + 1]);
                double span = b - a;
                double t = span <= 0 ? 0 : (Math.log(hz) - a) / span;
                return (float) (values[i] + (values[i + 1] - values[i]) * t);
            }
        }
        return values[last];
    }

    public synchronized boolean isApplied() {
        return applied;
    }

    public synchronized void markApplied(String value) {
        applied = true;
        signature = value;
    }

    public synchronized String signature() {
        return signature;
    }

    public synchronized void close() {
        if (effect != null) {
            try { release.invoke(effect); } catch (Throwable ignored) {}
        }
        if (loudnessEnhancer != null) {
            try { leRelease.invoke(loudnessEnhancer); } catch (Throwable ignored) {}
        }
        effect = null;
        loudnessEnhancer = null;
        bandCount = 0;
        wantedBands = 0;
        wantedFrameMs = 0f;
        applied = false;
        signature = "";
        // The stages are deliberately kept: close() is how a failed open() cleans up, and wiping
        // them there would delete the very reason the open failed right before the engine reports
        // it. open() clears them instead, so nothing carries over between attempts.
    }

    public synchronized String lastError() {
        return lastError;
    }

    public synchronized boolean isOpen() {
        if (effect == null) return false;
        try {
            return Boolean.TRUE.equals(hasControl.invoke(effect));
        } catch (Throwable error) {
            return false;
        }
    }

    private void enableLimiter() {
        if (effect == null || limiterCtor == null || setLimiterAll == null) {
            stage("limiter", "skip");
            return;
        }
        try {
            Object limiter = limiterCtor.newInstance(true, true, 0, limiterAttackMs,
                    limiterReleaseMs, limiterRatio, limiterThreshold, 0f);
            setLimiterAll.invoke(effect, limiter);
            stage("limiter", "ok");
        } catch (Throwable error) {
            // Not fatal: AOSP's default limiter is still armed, which protects the speaker, and
            // the curve is worth more than the shape. But say which one is actually in force.
            stage("limiter", "fail:" + describe(error));
        }
    }

    private void enableBaseCompression() {
        if (effect == null || mbcBandCtor == null || setMbcBandAll == null) {
            stage("mbc", "skip");
            return;
        }
        try {
            Object band = mbcBandCtor.newInstance(
                    true, 120f, 0.020f, 0.250f, 2.0f, -24f, 6f, -60f, 1.0f, 0f, 0f);
            setMbcBandAll.invoke(effect, 0, band);
            stage("mbc", "ok");
        } catch (Throwable error) {
            stage("mbc", "fail:" + describe(error));
        }
    }

    static String describe(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }
}
