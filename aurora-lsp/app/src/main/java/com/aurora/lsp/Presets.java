package com.aurora.lsp;

/**
 * Listening curves in dB, one value per equalizer band.
 *
 * <p>Bands are 80 / 200 / 500 / 1.5k / 4k / 10k Hz. Two rules shaped every curve here, and both
 * came out of listening rather than theory:
 *
 * <ol>
 *   <li><b>The muddy range stays down.</b> 150-600 Hz is where sound turns to mush on a phone
 *       speaker. Bass weight belongs at 80 Hz, not at 250-500: piling gain on the low midrange is
 *       what made earlier presets sound thick instead of deep.
 *   <li><b>Neighbours stay within 6 dB.</b> A curve that jumps 10 dB between adjacent bands sounds
 *       exaggerated on paper and broken in the ear - it is the "pulled too far" feeling.
 * </ol>
 *
 * <p>Every preset except {@code natural} moves at least one band by 5 dB, so switching is audible
 * rather than academic.
 */
public final class Presets {

    public static final String[] KEYS = {
            "flat", "natural", "harman", "burmester", "punch",
            "cinema", "vocal", "bright", "night"
    };
    public static final String[] NAMES = {
            "原厂平直", "自然均衡", "哈曼卡顿", "柏林之声", "现场低频",
            "影院包围", "人声清晰", "明亮解析", "夜间柔化"
    };
    public static final String[] NOTES = {
            "全部归零，等同于不叠加任何曲线。",
            "轻微抬一点厚度，几乎听不出修饰。",
            "80 Hz 加重量，浑浊区让开，厚而不糊。",
            "中高频打开，器乐一件件分得开。",
            "低频顶满但浑浊区下压，鼓点砸下来不糊。",
            "下盘厚、极高频收，声场往外推开。",
            "主唱贴耳，背景整体后退。",
            "齿音与镲片跳出来，细节透亮。",
            "高频收敛，小音量也不刺耳。"
    };

    /** dB per band, aligned with {@link AudioEq#BAND_HZ}. */
    private static final float[][] CURVES = {
            // 80    200    500   1.5k   4k    10k
            {0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 1, 1},
            {8, 2, -1, 1, 3, 2},
            {1, 0, -1, 3, 6, 6},
            {10, 4, -2, 2, 4, 3},
            {8, 3, -2, 0, 3, 2},
            {-4, -2, 1, 6, 4, 1},
            {-3, -1, 0, 3, 7, 8},
            {5, 0, 0, -3, -4, -5}
    };

    private Presets() {
    }

    public static int indexOf(String key) {
        if (key == null) return 0;
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return 0;
    }

    /**
     * Whether the key names a preset that exists.
     *
     * <p>{@link #indexOf} answers 0 for anything it does not know, which is the safe value for a
     * lookup but indistinguishable from a genuine "flat". Callers that want to tell a typo from a
     * real choice ask this instead.
     */
    public static boolean hasKey(String key) {
        if (key == null) return false;
        for (String candidate : KEYS) if (candidate.equals(key)) return true;
        return false;
    }

    public static float[] curve(int index) {
        int safe = Math.max(0, Math.min(CURVES.length - 1, index));
        return CURVES[safe];
    }
}