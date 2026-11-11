package com.aurora.lsp

import android.os.Build
import android.provider.Settings

/**
 * What the module found when it probed this device at boot.
 *
 * Written by {@code AuroraProbe} inside system_server, read here. Empty when the module never ran,
 * which is itself the most common reason "it does nothing".
 */
data class DeviceProfile(
    val stage: Int = -1,
    val reason: String = "",
    val model: String = "",
    val soc: String = "",
    val sdk: String = "",
    val miui: String = "",
    val effects: String = "",
    val outputs: String = "",
    val route: String = "",
    val probedAt: Long = 0L,
    val engineVersion: Int = -1,
    /**
     * What the engine last reported about this device. The probe says whether the chain can be
     * built; these say whether writes actually land - the 17 Ultra could pass the probe while
     * rejecting every band, and that gap was invisible in the reported profiles.
     */
    val backendState: String = "",
    val backendMessage: String = "",
    val bandHz: String = "",
    /** Which stages the effect chain really got: "bands=6/6 limiter=ok mbc=skip". */
    val stages: String = ""
) {
    /** Probe never wrote anything: the module is not running at all. */
    val probed: Boolean get() = probedAt > 0L

    /** Every check passed. */
    val supported: Boolean get() = probed && stage == 0

    val effectNames: List<String>
        get() = effects.split(';')
            .mapNotNull { it.substringBefore('|').takeIf { name -> name.isNotBlank() } }

    /**
     * True when the code running inside system_server came from a different install than the app.
     * Happens every time someone updates without rebooting: the app shows new presets, the engine
     * is still playing the old ones.
     */
    fun staleEngine(appVersionCode: Int): Boolean =
        engineVersion > 0 && appVersionCode > 0 && engineVersion != appVersionCode

    fun stageLabel(): String = when (stage) {
        0 -> "链路可访问"
        1 -> "取不到 AudioManager"
        2 -> "取不到播放会话信息"
        3 -> "本机没有可用的均衡器实现"
        else -> "未检测"
    }

    companion object {
        private val KEYS = listOf(
            AuroraProbe.K_STAGE,
            AuroraProbe.K_REASON,
            AuroraProbe.K_MODEL,
            AuroraProbe.K_SOC,
            AuroraProbe.K_SDK,
            AuroraProbe.K_MIUI,
            AuroraProbe.K_EFFECTS,
            AuroraProbe.K_OUTPUTS,
            AuroraProbe.K_ROUTE,
            AuroraProbe.K_PROBED_AT,
            AuroraEngine.R_ENGINE_VERSION,
            AuroraEngine.R_STATE,
            AuroraEngine.R_MESSAGE,
            AuroraEngine.R_BANDHZ,
            AuroraEngine.R_STAGES
        )

        /** Reads off the main thread: ten Settings.Global lookups are ten binder round trips. */
        fun read(resolver: android.content.ContentResolver): DeviceProfile {
            fun get(key: String): String = try {
                Settings.Global.getString(resolver, key).orEmpty().let {
                    if (it == "null") "" else it
                }
            } catch (t: Throwable) {
                ""
            }

            val values = KEYS.map(::get)
            return DeviceProfile(
                stage = values[0].toIntOrNull() ?: -1,
                reason = values[1],
                model = values[2].ifBlank { "${Build.MODEL}/${Build.DEVICE}" },
                soc = values[3].ifBlank { "${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL}" },
                sdk = values[4].ifBlank { Build.VERSION.SDK_INT.toString() },
                miui = values[5],
                effects = values[6],
                outputs = values[7],
                route = values[8],
                probedAt = values[9].toLongOrNull() ?: 0L,
                engineVersion = values[10].toIntOrNull() ?: -1,
                backendState = values[11],
                backendMessage = values[12],
                bandHz = values[13],
                stages = values[14]
            )
        }
    }
}