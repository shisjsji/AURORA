package com.aurora.lsp;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies the AURORA curve to every live playback session.
 */
public final class AuroraEngine {

    private static final String TAG = "AURORA-LSP";

    public static final String K_ENABLED = "aurora_enabled";
    public static final String K_PANIC = "aurora_panic";
    public static final String K_PRESET = "aurora_preset";
    public static final String K_STRENGTH = "aurora_strength";
    public static final String K_GEQ = "aurora_geq";
    public static final String K_GEQ_SAVED = "aurora_geq_saved";
    public static final String K_SELFTEST = "aurora_selftest";
    public static final String K_USER_PRESETS = "aurora_user_presets";
    public static final String K_APP_MAP = "aurora_app_map";
    /** Advanced settings: spread (stereo widening) and perceived loudness. */
    public static final String K_SPREAD = "aurora_spread";
    public static final String K_LOUDNESS = "aurora_loudness";
    /**
     * Precise mode: ten pre-EQ bands at a 20 ms frame instead of six at 10 ms.
     *
     * <p>Costs about 10 ms of extra latency, so it is off by default and stays opt-in: lip sync in
     * video and the feel of a game both notice, and neither is worth a slightly better bass
     * contour unless the listener asked for it.
     */
    public static final String K_PRECISE = "aurora_precise";
    /** Per-device adaptation written by the app after fetching the matching device pack. */
    public static final String K_PACK = "aurora_device_pack";

    public static final String R_STATE = "aurora_backend_state";
    public static final String R_MESSAGE = "aurora_backend_message";
    public static final String R_BANDS = "aurora_backend_bands";
    public static final String R_UPDATED = "aurora_backend_updated";
    public static final String R_SESSIONS = "aurora_backend_sessions";
    public static final String R_APPLIED = "aurora_backend_applied";
    public static final String R_LOG = "aurora_backend_log";
    public static final String R_ENGINE_VERSION = "aurora_engine_version";
    /** The band table actually in force, so the UI never labels 250 Hz as 80 Hz. */
    public static final String R_BANDHZ = "aurora_backend_bandhz";
    /** "pack:<name>", "fallback", or both, when the device runs adapted settings. */
    public static final String R_ADAPT = "aurora_backend_adapt";
    /**
     * Which stages the effect chain really has: "bands=6/6 limiter=ok mbc=ok loudness=ok".
     *
     * <p>These writes are all reflective and every one of them used to fail silently, so a phone
     * running a half-configured chain looked identical to a working one. This is the key that
     * tells them apart, and the one a device pack can be written against.
     */
    public static final String R_STAGES = "aurora_backend_stages";

    private static final int MAX_SESSIONS = 8;
    private static final int LOG_LINES = 50;
    private static final long PLAYBACK_DEBOUNCE_MS = 60L;
    /** How long a session may go unseen before its effect is released. */
    private static final long STALE_TTL_MS = 10_000L;

    /** Loudness compensation shape per band, scaled by how far the volume sits below 60%. */
    private static final float[] LOUDNESS_SHAPE = {1.0f, 0.5f, 0f, -0.1f, 0.3f, 0.6f};
    private static final float MAX_LOUDNESS_BOOST_DB = 4f;

    /**
     * Per-band ceiling. 80 Hz and 10 kHz are the widest; 200 Hz stays low because pushing the
     * muddy range makes everything sound thick. 500 Hz has headroom but is rarely needed.
     */
    private static final float[] MAX_BOOST_BY_BAND = {8f, 4f, 6f, 8f, 8f, 8f};
    /** Same ceilings on a route with its own bass: only 200 Hz differs, 4 dB becomes 6 dB. */
    private static final float[] HEADPHONE_BOOST_BY_BAND = {8f, 6f, 6f, 8f, 8f, 8f};

    /**
     * Pre-1.3.1 the limiter constructor lookup never matched, so the engine ran the AOSP default
     * brickwall (-2 dBFS, 10:1, 1 ms attack) - the artifacts reported as 破音/炸音. These gentler
     * defaults are what actually lands now; device packs override them per model.
     */
    static final AudioEq.LimiterConfig DEFAULT_LIMITER =
            new AudioEq.LimiterConfig(-6f, 6f, 5f, 80f);

    /**
     * Known-good band table for devices whose DSP rejects the 80 Hz / 10 kHz endpoints. AOSP's own
     * default band layout starts at 220 Hz, so this is the table every pre-1.2.1 build used.
     */
    static final float[] FALLBACK_HZ = {250f, 500f, 1000f, 2000f, 4000f, 6300f};

    private static volatile AuroraEngine instance;

    private final Context context;
    private final Map<Integer, AudioEq> effects = new HashMap<>();
    private final Map<Integer, Long> lastSeen = new HashMap<>();
    private final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
    private final java.text.SimpleDateFormat clock =
            new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
    private final Map<Integer, String> uidPackages = new HashMap<>();
    /** Last unknown preset key already written to the log, so a bad value is reported once. */
    private String lastUnknownPreset;
    private Method getActiveConfigs;
    private Method getSessionId;
    private Method getPlayerState;
    private Method getClientUid;
    private Method getAudioDeviceInfo;
    private boolean reflected;
    private String lastError = "";
    private String lastSignature = "";
    private int lastSelftestToken;
    private long selftestUntil;
    private Config lastGood;

    /** Set once the default/pack table is rejected and the legacy table took over; cleared by reboot. */
    private boolean fallbackEngaged;
    /** Cached parse of aurora_device_pack: JSON is only walked when the raw string changes. */
    private String lastPackRaw = "";
    private float[] packBandHz;
    private float[] packMaxBoost;
    private AudioEq.LimiterConfig packLimiter;
    private String packName;

    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    Handler handler = engineHandler;
                    if (handler == null) return;
                    handler.removeCallbacks(playbackTick);
                    handler.postDelayed(playbackTick, PLAYBACK_DEBOUNCE_MS);
                }
            };

    private final Runnable playbackTick = new Runnable() {
        @Override
        public void run() {
            tick();
        }
    };

    private volatile Handler engineHandler;

    private AuroraEngine(Context context) {
        this.context = context;
    }

    public static AuroraEngine get(Context context) {
        if (instance == null) {
            synchronized (AuroraEngine.class) {
                if (instance == null) instance = new AuroraEngine(context);
            }
        }
        return instance;
    }

    public void attach(Handler handler) {
        this.engineHandler = handler;
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager != null) {
                manager.registerAudioPlaybackCallback(playbackCallback, handler);
                Log.i(TAG, "playback callback registered");
            }
        } catch (Throwable error) {
            Log.w(TAG, "playback callback unavailable: " + AudioEq.describe(error));
        }
    }

    public void tick() {
        Config config = read();
        if (config.panic) {
            releaseAll();
            report("panic", "已通过 aurora_panic 停机，均衡器全部释放。", null, 0);
            return;
        }
        if (!config.enabled) {
            releaseAll();
            report("disabled", "叠加已关闭，音频走原厂链路。", null, 0);
            return;
        }
        Map<Integer, SessionInfo> sessions = activeSessions();
        if (sessions.isEmpty()) {
            releaseStale(android.os.SystemClock.elapsedRealtime());
            report("idle", "当前没有正在播放的会话，等待播放开始。", null, 0);
            return;
        }

        boolean testing = selftestActive();
        int applied = 0;
        String failure = "";
        String stages = "";
        float[] lastGains = null;
        boolean fellBackThisTick = false;

        long now = android.os.SystemClock.elapsedRealtime();
        int volume = musicVolumePercent();

        int skipped = 0;
        for (Map.Entry<Integer, SessionInfo> entry : sessions.entrySet()) {
            int session = entry.getKey();
            SessionInfo info = entry.getValue();
            lastSeen.put(session, now);

            // The curve is authored on six bands and stays that way everywhere the user can see
            // it; only what reaches the DSP is resampled, onto however many bands the chain was
            // opened with. Six in, six out when precise mode is off, so nothing changes there.
            float[] gains = testing ? selftestGains() : buildGains(config, info, volume);
            // The table is picked per session so a fallback triggered by an earlier session in
            // this same tick is already in force for the later ones.
            float[] table = fallbackEngaged ? FALLBACK_HZ : activeTable(config);
            float[] spread = buildSpread(config, info);
            int bands = config.preEqBands();
            float[] writeGains = AudioEq.expandGains(gains, table, bands);
            float[] writeSpread = spread == null ? null : AudioEq.expandGains(spread, table, bands);
            String desired = desiredSignature(writeGains, config, table, bands);
            lastGains = gains;

            AudioEq eq = effects.get(session);
            if (eq != null && (!eq.isOpen()
                    || !eq.matchesArchitecture(config.preEqBands(), config.frameMs()))) {
                // Two ways an effect outlives its usefulness: the session dropped its chain (a
                // track torn down without a playback callback leaves a handle whose writes all
                // fail), or the band count changed - which cannot be altered on a live chain, so
                // precision mode has to rebuild it rather than write into a six-band effect.
                eq.close();
                effects.remove(session);
                eq = null;
            }
            if (eq == null) {
                if (effects.size() >= MAX_SESSIONS) {
                    skipped++;
                    continue;
                }
                eq = new AudioEq();
                // open() releases the effect itself on every failure path, so a session that
                // cannot be controlled does not keep an unconfigured chain bound to it.
                if (!eq.open(session, config.limiter, config.preEqBands(), config.frameMs())) {
                    // A session that keeps refusing to open is retried on every tick and on every
                    // playback callback. That is the right thing to do (the chain is rebuilt when
                    // the track restarts), but it would also overwrite the reported reason 30
                    // times a minute; the first failure is the informative one.
                    if (failure.isEmpty()) {
                        failure = eq.lastError();
                        stages = eq.stages();
                    }
                    continue;
                }
                effects.put(session, eq);
            }
            // Only a working chain contributes to the reported stages. If one session failed to
            // open, its stages are cleared by close(), and taking them from a healthy sibling
            // would report the working one - the failure would look like a success.
            if (failure.isEmpty()) stages = eq.stages();
            if (eq.isApplied() && desired.equals(eq.signature()) && !testing) {
                applied++;
                continue;
            }
            if (!eq.apply(writeGains, writeSpread, config.loudnessMb, true,
                    AudioEq.expandTable(table, bands))) {
                // The primary table was rejected: a device whose DSP does not accept the 80 Hz
                // or 10 kHz endpoints still gets a curve, on the table that predates them.
                if (!fallbackEngaged && !sameTable(table, FALLBACK_HZ)
                        && eq.apply(AudioEq.expandGains(gains, FALLBACK_HZ, bands),
                                spread == null ? null
                                        : AudioEq.expandGains(spread, FALLBACK_HZ, bands),
                                config.loudnessMb, true,
                                AudioEq.expandTable(FALLBACK_HZ, bands))) {
                    fallbackEngaged = true;
                    fellBackThisTick = true;
                    eq.markApplied(desiredSignature(
                            AudioEq.expandGains(gains, FALLBACK_HZ, bands),
                            config, FALLBACK_HZ, bands));
                    applied++;
                    continue;
                }
                failure = eq.lastError();
                continue;
            }
            eq.markApplied(desired);
            applied++;
        }

        releaseStale(now);
        if (applied == 0 && !failure.isEmpty()) {
            report("error", "写入失败：" + failure, lastGains, 0, tableFor(config),
                    adaptString(config), stages);
            return;
        }
        String message = testing
                ? "自检中：全频段 -12 dB，6 秒后自动恢复。"
                : "已写入 " + applied + " 个播放会话。";
        if (fellBackThisTick) message += " 本机频点被拒，已回退 250 Hz–6.3 kHz 兼容频点。";
        if (config.packName != null) message += " 设备适配：" + config.packName + "。";
        if (config.precise) message += " 精确模式：10 段 / 20 ms 帧。";
        if (!failure.isEmpty()) message += " 部分会话失败：" + failure;
        if (skipped > 0) message += " 会话数已达上限，跳过 " + skipped + " 个。";
        report(testing ? "selftest" : "active", message, lastGains, applied,
                tableFor(config), adaptString(config), stages);
    }

    private float[] activeTable(Config config) {
        return config.bandHz != null ? config.bandHz : AudioEq.BAND_HZ;
    }

    private float[] tableFor(Config config) {
        return fallbackEngaged ? FALLBACK_HZ : activeTable(config);
    }

    private String adaptString(Config config) {
        String adapt = "";
        if (config.packName != null) adapt = "pack:" + config.packName;
        if (fallbackEngaged) adapt += (adapt.isEmpty() ? "" : "+") + "fallback";
        return adapt;
    }

    /**
     * The full identity of a write: curve, widening, loudness, band table and band count.
     *
     * <p>The band count has to be in here. Signature is what decides whether the next tick skips
     * the write, and after leaving precise mode the six-band curve and the ten-band curve can
     * round to the same values - skipping then leaves the old chain in place with the old shape.
     */
    private static String desiredSignature(float[] gains, Config config, float[] table, int bands) {
        return signature(gains) + "s" + config.spread + "l" + config.loudnessMb
                + "t" + tableTag(table) + "n" + bands;
    }

    private static String tableTag(float[] table) {
        if (table == FALLBACK_HZ) return "legacy";
        if (table == AudioEq.BAND_HZ) return "default";
        StringBuilder tag = new StringBuilder("p");
        for (float hz : table) tag.append(Math.round(hz)).append('.');
        return tag.toString();
    }

    private static boolean sameTable(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 0.5f) return false;
        }
        return true;
    }

    /**
     * Stereo widening shape, trimmed per route.
     *
     * <p>Unchanged on speakers: the two drivers are centimetres apart, so a wide inter-channel
     * difference is most of what sells the effect. On headphones and earbuds the channels are
     * already isolated and the same amount is roughly twice as strong, so it is scaled back
     * rather than left to sound phasey.
     */
    static float[] buildSpread(Config config, SessionInfo info) {
        if (config.spread <= 0) return null;
        // Non-uniform: the ear localises high frequencies by inter-aural level difference, so
        // the top band gets the full amount, 4k gets most of it, 1.5k gets half. Bass stays
        // centred - separating it makes the low end thin, not wide.
        float amount = config.spread * routeSpreadScale(info);
        return new float[]{0f, 0f, 0f, amount * 0.5f, amount * 0.8f, amount};
    }

    /** How much of the full widening a route tolerates. Isolated channels get less. */
    private static float routeSpreadScale(SessionInfo info) {
        int type = info == null ? -1 : info.deviceType;
        switch (type) {
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case android.media.AudioDeviceInfo.TYPE_USB_HEADSET:
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return 0.6f;
            default:
                return 1f;
        }
    }

    private void releaseStale(long now) {
        for (Integer session : staleSessions(lastSeen, now, STALE_TTL_MS)) {
            lastSeen.remove(session);
            AudioEq eq = effects.remove(session);
            if (eq != null) eq.close();
        }
    }

    private void releaseAll() {
        for (AudioEq eq : effects.values()) eq.close();
        effects.clear();
        lastSeen.clear();
    }

    private Map<Integer, SessionInfo> activeSessions() {
        Map<Integer, SessionInfo> result = new HashMap<>();
        if (!reflect()) return result;
        try {
            Object manager = context.getSystemService(Context.AUDIO_SERVICE);
            Object value = getActiveConfigs.invoke(manager);
            if (!(value instanceof List)) return result;
            for (Object config : (List<?>) value) {
                int session = (Integer) getSessionId.invoke(config);
                int state = (Integer) getPlayerState.invoke(config);
                if (session <= 0 || session > 1_000_000) continue;
                if (state != 2) continue;
                int uid = uidOf(config);
                result.put(session, new SessionInfo(session, uid, deviceTypeOf(config), packageOf(uid)));
            }
        } catch (Throwable error) {
            lastError = AudioEq.describe(error);
            Log.w(TAG, "session scan failed: " + lastError);
        }
        return result;
    }

    private int uidOf(Object config) {
        if (getClientUid == null) return -1;
        try {
            Object value = getClientUid.invoke(config);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private int deviceTypeOf(Object config) {
        if (getAudioDeviceInfo == null) return -1;
        try {
            Object value = getAudioDeviceInfo.invoke(config);
            if (value instanceof android.media.AudioDeviceInfo) {
                return ((android.media.AudioDeviceInfo) value).getType();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private String packageOf(int uid) {
        if (uid <= 0) return null;
        if (uidPackages.containsKey(uid)) return uidPackages.get(uid);
        String resolved = null;
        try {
            String[] names = context.getPackageManager().getPackagesForUid(uid);
            if (names != null && names.length > 0) resolved = names[0];
        } catch (Throwable ignored) {
        }
        uidPackages.put(uid, resolved);
        return resolved;
    }

    private boolean reflect() {
        if (reflected) return getActiveConfigs != null;
        reflected = true;
        try {
            Class<?> managerClass = Class.forName("android.media.AudioManager");
            getActiveConfigs = managerClass.getMethod("getActivePlaybackConfigurations");
            Class<?> configClass = Class.forName("android.media.AudioPlaybackConfiguration");
            getSessionId = configClass.getMethod("getSessionId");
            getPlayerState = configClass.getMethod("getPlayerState");
            try { getClientUid = configClass.getMethod("getClientUid"); } catch (Throwable ignored) {}
            try { getAudioDeviceInfo = configClass.getMethod("getAudioDeviceInfo"); } catch (Throwable ignored) {}
            return true;
        } catch (Throwable error) {
            lastError = AudioEq.describe(error);
            getActiveConfigs = null;
            Log.w(TAG, "playback configuration API unavailable: " + lastError);
            return false;
        }
    }

    private boolean selftestActive() {
        int token = 0;
        try {
            token = Settings.Global.getInt(context.getContentResolver(), K_SELFTEST, 0);
        } catch (Throwable ignored) {
        }
        if (token != lastSelftestToken) {
            lastSelftestToken = token;
            selftestUntil = android.os.SystemClock.elapsedRealtime() + 6000L;
        }
        return android.os.SystemClock.elapsedRealtime() < selftestUntil;
    }

    private static float[] selftestGains() {
        float[] flat = new float[AudioEq.BANDS];
        for (int i = 0; i < flat.length; i++) flat[i] = -AudioEq.MAX_DB;
        return flat;
    }

    private int musicVolumePercent() {
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) return 100;
            int max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) return 100;
            int current = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
            return Math.max(0, Math.min(100, Math.round(current * 100f / max)));
        } catch (Throwable ignored) {
            return 100;
        }
    }

    static float[] buildGains(Config config, SessionInfo info, int volumePercent) {
        float[] base = resolveCurve(config, info);
        float scale = config.strength / 100f;
        float[] scaled = new float[AudioEq.BANDS];
        float peak = 0f;
        for (int i = 0; i < AudioEq.BANDS; i++) {
            scaled[i] = base[i] * scale;
            peak = Math.max(peak, Math.abs(scaled[i]));
        }
        float fit = peak > AudioEq.MAX_DB ? AudioEq.MAX_DB / peak : 1f;
        float[] out = new float[AudioEq.BANDS];
        for (int i = 0; i < AudioEq.BANDS; i++) {
            out[i] = scaled[i] * fit;
        }

        applyVolumeCompensation(out, volumePercent, info);
        applyRouteTrim(out, info);

        float loudest = -AudioEq.MAX_DB;
        for (int i = 0; i < AudioEq.BANDS; i++) loudest = Math.max(loudest, out[i]);
        if (loudest > 6f) {
            float shift = loudest - 6f;
            for (int i = 0; i < AudioEq.BANDS; i++) out[i] -= shift;
        }

        for (int i = 0; i < AudioEq.BANDS; i++) {
            float cap = capForBand(config, info, i);
            out[i] = Math.max(-AudioEq.MAX_DB, Math.min(cap, out[i]));
        }
        return out;
    }

    private static float[] resolveCurve(Config config, SessionInfo info) {
        String assigned = info == null || info.packageName == null
                ? null
                : config.appMap.get(info.packageName);
        if (assigned != null) {
            float[] saved = config.userPresets.get(assigned);
            if (saved != null) return saved;
            if (Presets.isBuiltIn(assigned)) return Presets.curve(Presets.indexOf(assigned));
        }
        if (config.userCurve != null) return config.userCurve;
        return Presets.curve(Presets.indexOf(config.preset));
    }

    /**
     * Lifts the bass at low volume, less on routes that already deliver it.
     *
     * <p>The compensation exists to make up for a small speaker losing its bottom octave as the
     * volume drops. A headphone or earbud does not lose it, and the same lift there reads as
     * boomy rather than full, so it is scaled back instead of being shared blind.
     */
    private static void applyVolumeCompensation(float[] gains, int volumePercent, SessionInfo info) {
        if (volumePercent >= 60) return;
        float depth = (60 - volumePercent) / 60f;
        float amount = MAX_LOUDNESS_BOOST_DB * depth * routeLoudnessScale(info);
        for (int i = 0; i < gains.length && i < LOUDNESS_SHAPE.length; i++) {
            gains[i] += amount * LOUDNESS_SHAPE[i];
        }
    }

    /** Speaker-tuned lift, kept whole only where the driver is the reason for it. */
    private static float routeLoudnessScale(SessionInfo info) {
        int type = info == null ? -1 : info.deviceType;
        switch (type) {
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case android.media.AudioDeviceInfo.TYPE_USB_HEADSET:
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return 0.6f;
            default:
                return 1f;
        }
    }

    private static float capForBand(Config config, SessionInfo info, int index) {
        float[] caps = config.maxBoost != null ? config.maxBoost : capsForRoute(info);
        return caps[Math.min(index, caps.length - 1)];
    }

    /**
     * Boost ceilings for the compiled-in table; a device pack still overrides them wholesale.
     *
     * <p>Only the 200 Hz value moves: 4 dB there keeps a phone speaker from turning thick, a
     * limitation a headphone does not share. The rest are already at the preamp limit and could
     * not bind even if they were raised.
     */
    private static float[] capsForRoute(SessionInfo info) {
        int type = info == null ? -1 : info.deviceType;
        switch (type) {
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case android.media.AudioDeviceInfo.TYPE_USB_HEADSET:
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return HEADPHONE_BOOST_BY_BAND;
            default:
                return MAX_BOOST_BY_BAND;
        }
    }

    private static void applyRouteTrim(float[] gains, SessionInfo info) {
        int type = info == null ? -1 : info.deviceType;
        switch (type) {
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case android.media.AudioDeviceInfo.TYPE_USB_HEADSET:
                gains[0] -= 3f;
                gains[1] -= 1f;
                break;
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                gains[0] -= 2f;
                break;
        }
    }

    private static String signature(float[] gains) {
        StringBuilder builder = new StringBuilder();
        for (float gain : gains) {
            builder.append(Math.round(gain * 16)).append(',');
        }
        return builder.toString();
    }

    private void report(String state, String message, float[] gains, int applied,
                        float[] table, String adapt) {
        report(state, message, gains, applied, table, adapt, "");
    }

    private void report(String state, String message, float[] gains, int applied,
                        float[] table, String adapt, String stages) {
        String bands = gains == null ? "" : join(gains);
        String sessions = join(effects.keySet());
        String bandHz = table == null ? "" : join(table);
        String chain = stages == null ? "" : stages;
        // The stages belong in the signature: a limiter that starts or stops landing changes
        // nothing else in this tuple, and skipping the write would hide it until the next state
        // change - exactly the silence this key exists to break.
        String signature = state + '|' + message + '|' + bands + '|' + applied + '|' + sessions
                + '|' + chain;
        if (signature.equals(lastSignature)) return;
        lastSignature = signature;
        try {
            ContentResolver resolver = context.getContentResolver();
            Settings.Global.putString(resolver, R_STATE, state);
            Settings.Global.putString(resolver, R_MESSAGE, message);
            Settings.Global.putString(resolver, R_BANDS, bands);
            Settings.Global.putString(resolver, R_SESSIONS, sessions);
            Settings.Global.putString(resolver, R_APPLIED, String.valueOf(applied));
            Settings.Global.putString(resolver, R_BANDHZ, bandHz);
            Settings.Global.putString(resolver, R_ADAPT, adapt == null ? "" : adapt);
            Settings.Global.putString(resolver, R_STAGES, chain);
            Settings.Global.putLong(resolver, R_UPDATED, System.currentTimeMillis());
            record(resolver, state, message);
        } catch (Throwable error) {
            Log.w(TAG, "report failed: " + AudioEq.describe(error));
        }
        Log.i(TAG, "backend=" + state + ": " + message + " sessions=" + sessions);
    }

    private void report(String state, String message, float[] gains, int applied) {
        report(state, message, gains, applied, null, "", "");
    }

    private void record(ContentResolver resolver, String state, String message) {
        recent.addLast(clock.format(new java.util.Date()) + " " + state + " " + message);
        while (recent.size() > LOG_LINES) recent.removeFirst();
        StringBuilder builder = new StringBuilder();
        for (String line : recent) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(line);
        }
        Settings.Global.putString(resolver, R_LOG, builder.toString());
    }

    private static String join(float[] gains) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < gains.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(Math.round(gains[i] * 10));
        }
        return builder.toString();
    }

    private static String join(Set<Integer> values) {
        StringBuilder builder = new StringBuilder();
        for (Integer value : values) {
            if (builder.length() > 0) builder.append(',');
            builder.append(value);
        }
        return builder.toString();
    }

    private Config read() {
        Config config = new Config();
        try {
            ContentResolver resolver = context.getContentResolver();
            config.enabled = Settings.Global.getInt(resolver, K_ENABLED, 0) == 1;
            config.panic = Settings.Global.getInt(resolver, K_PANIC, 0) == 1;
            config.preset = Settings.Global.getString(resolver, K_PRESET);
            config.strength = clamp(Settings.Global.getInt(resolver, K_STRENGTH, 100), 0, 100);
            config.userCurve = parseCurve(Settings.Global.getString(resolver, K_GEQ));
            config.userPresets = parseUserPresets(Settings.Global.getString(resolver, K_USER_PRESETS));
            config.appMap = parseAppMap(Settings.Global.getString(resolver, K_APP_MAP));
            config.spread = clamp(Settings.Global.getInt(resolver, K_SPREAD, 0), 0, 80) / 10f;
            config.loudnessMb = clamp(Settings.Global.getInt(resolver, K_LOUDNESS, 0), 0, 500);
            config.precise = Settings.Global.getInt(resolver, K_PRECISE, 0) == 1;
            parsePack(config, Settings.Global.getString(resolver, K_PACK));
            // An unknown key still resolves to the flat curve - that is the safe value - but it
            // used to do so silently, which looks exactly like "the module does nothing". Say it
            // once per distinct bad value instead of every tick.
            String stored = config.preset;
            boolean unknown = stored != null && !stored.isEmpty()
                    && !"custom".equals(stored) && !Presets.hasKey(stored);
            if (unknown && !stored.equals(lastUnknownPreset)) {
                lastUnknownPreset = stored;
                record(resolver, "warn", "未知预设 " + stored + "，按原厂平直处理");
            } else if (!unknown) {
                lastUnknownPreset = null;
            }
        } catch (Throwable error) {
            Log.w(TAG, "config read failed: " + AudioEq.describe(error));
            if (lastGood != null) return lastGood;
            config.enabled = false;
        }
        lastGood = config;
        return config;
    }

    static float[] parseCurve(String raw) {
        if (raw == null || raw.isEmpty() || raw.equals("null")) return null;
        String[] parts = raw.split(",");
        if (parts.length != AudioEq.BANDS) return null;
        float[] curve = new float[AudioEq.BANDS];
        try {
            for (int i = 0; i < AudioEq.BANDS; i++) {
                curve[i] = Integer.parseInt(parts[i].trim()) / 10f;
            }
        } catch (NumberFormatException error) {
            return null;
        }
        return curve;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static Map<String, float[]> parseUserPresets(String raw) {
        Map<String, float[]> out = new HashMap<>();
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id");
                JSONArray curve = item.optJSONArray("curve");
                if (id == null || id.isEmpty() || curve == null) continue;
                if (curve.length() != AudioEq.BANDS) continue;
                float[] values = new float[AudioEq.BANDS];
                for (int b = 0; b < AudioEq.BANDS; b++) {
                    values[b] = (float) (curve.optDouble(b, 0) / 10.0);
                }
                out.put(id, values);
            }
        } catch (Throwable error) {
            Log.w(TAG, "user presets unreadable: " + AudioEq.describe(error));
        }
        return out;
    }

    private static Map<String, String> parseAppMap(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return out;
        try {
            JSONObject object = new JSONObject(raw);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = object.optString(key);
                if (key != null && !key.isEmpty() && value != null && !value.isEmpty()
                        && !"null".equals(value)) {
                    out.put(key, value);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "app map unreadable: " + AudioEq.describe(error));
        }
        return out;
    }

    /**
     * Parses the device pack into the config. Every field is range-checked: a malformed or
     * hostile pack degrades to the defaults instead of reaching the audio chain.
     */
    private void parsePack(Config config, String raw) {
        if (raw == null || raw.isEmpty() || raw.equals("null")) {
            lastPackRaw = "";
            return;
        }
        if (raw.equals(lastPackRaw)) {
            config.bandHz = packBandHz;
            config.maxBoost = packMaxBoost;
            config.limiter = packLimiter != null ? packLimiter : DEFAULT_LIMITER;
            config.packName = packName;
            return;
        }
        String name = null;
        float[] bandHz = null;
        float[] maxBoost = null;
        AudioEq.LimiterConfig limiter = null;
        try {
            JSONObject object = new JSONObject(raw);
            String rawName = object.optString("name");
            name = rawName == null || rawName.isEmpty() ? null : rawName;
            // The JSON is unwrapped here and validated by the static helpers below, which take plain
            // numbers: that keeps the rules testable on a plain JVM, where org.json only exists as a
            // stub that throws.
            JSONArray bands = object.optJSONArray("bandHz");
            if (bands != null) bandHz = bandHzFromPack(toDoubles(bands));
            JSONArray boosts = object.optJSONArray("maxBoost");
            if (boosts != null) maxBoost = maxBoostFromPack(toDoubles(boosts));
            limiter = limiterFromPack(
                    object.optDouble("thresholdDb", Double.NaN),
                    object.optDouble("ratio", Double.NaN),
                    object.optDouble("attackMs", Double.NaN),
                    object.optDouble("releaseMs", Double.NaN));
        } catch (Throwable error) {
            Log.w(TAG, "device pack unreadable: " + AudioEq.describe(error));
            return;
        }
        lastPackRaw = raw;
        packBandHz = bandHz;
        packMaxBoost = maxBoost;
        packLimiter = limiter;
        packName = name;
        config.bandHz = packBandHz;
        config.maxBoost = packMaxBoost;
        config.limiter = packLimiter != null ? packLimiter : DEFAULT_LIMITER;
        config.packName = packName;
    }

    private static double[] toDoubles(JSONArray array) {
        double[] values = new double[array.length()];
        for (int i = 0; i < values.length; i++) values[i] = array.optDouble(i, 0);
        return values;
    }

    /**
     * Band table from a device pack, or null when the pack's numbers cannot be trusted: six bands,
     * every one inside the audible window, strictly ascending. A pack that fails any of those is
     * dropped whole, because a half applied table would move the filters onto the wrong notes.
     */
    static float[] bandHzFromPack(double[] values) {
        if (values == null || values.length != AudioEq.BANDS) return null;
        float[] out = new float[AudioEq.BANDS];
        for (int i = 0; i < AudioEq.BANDS; i++) {
            out[i] = (float) values[i];
            if (out[i] <= 20f || out[i] >= 20000f) return null;
            if (i > 0 && out[i] <= out[i - 1]) return null;
        }
        return out;
    }

    /** Per-band boost ceilings from a pack: six values, each between 0 and +12 dB. */
    static float[] maxBoostFromPack(double[] values) {
        if (values == null || values.length != AudioEq.BANDS) return null;
        float[] out = new float[AudioEq.BANDS];
        for (int i = 0; i < AudioEq.BANDS; i++) {
            out[i] = (float) values[i];
            if (out[i] < 0f || out[i] > AudioEq.MAX_DB) return null;
        }
        return out;
    }

    /**
     * Limiter settings from a pack. All four must be present and inside the window the audio chain
     * can actually honour; a ratio below 1 or an attack of zero would turn the limiter off or make
     * it chatter, so a pack that asks for one is ignored rather than clamped.
     */
    static AudioEq.LimiterConfig limiterFromPack(double threshold, double ratio, double attack,
                                                 double release) {
        if (Double.isNaN(threshold) || Double.isNaN(ratio) || Double.isNaN(attack)
                || Double.isNaN(release)) {
            return null;
        }
        if (threshold < -24f || threshold > 0f) return null;
        if (ratio < 1f || ratio > 20f) return null;
        if (attack < 1f || attack > 100f) return null;
        if (release < 20f || release > 500f) return null;
        return new AudioEq.LimiterConfig(
                (float) threshold, (float) ratio, (float) attack, (float) release);
    }

    /** Sessions nobody has touched for longer than the TTL, oldest first. */
    static List<Integer> staleSessions(Map<Integer, Long> lastSeen, long now, long ttlMs) {
        List<Integer> gone = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : lastSeen.entrySet()) {
            if (now - entry.getValue() > ttlMs) gone.add(entry.getKey());
        }
        return gone;
    }

    static final class Config {
        boolean enabled;
        boolean panic;
        String preset;
        int strength = 100;
        float[] userCurve;
        Map<String, float[]> userPresets = new HashMap<>();
        Map<String, String> appMap = new HashMap<>();
        /** Stereo widening, 0–8 dB, applied above 500 Hz. */
        float spread;
        /** LoudnessEnhancer target gain in millibels, 0–500 (0–5 dB). */
        int loudnessMb;
        /** Device pack overrides; null means the compiled-in defaults. */
        float[] bandHz;
        float[] maxBoost;
        AudioEq.LimiterConfig limiter = DEFAULT_LIMITER;
        String packName;
        /**
         * Precise mode: open the chain with ten bands at a 20 ms frame instead of the platform's
         * six at 10 ms. Read from settings every tick, so the engine can notice a change and
         * rebuild the effect - a band count cannot be altered on a live chain.
         */
        boolean precise;

        int preEqBands() {
            return precise ? AudioEq.PRECISE_BANDS : AudioEq.DEFAULT_BANDS;
        }

        float frameMs() {
            return precise ? AudioEq.PRECISE_FRAME_MS : 0f;
        }
    }

    static final class SessionInfo {
        final int session;
        final int uid;
        final int deviceType;
        final String packageName;

        SessionInfo(int session, int uid, int deviceType, String packageName) {
            this.session = session;
            this.uid = uid;
            this.deviceType = deviceType;
            this.packageName = packageName;
        }
    }
}