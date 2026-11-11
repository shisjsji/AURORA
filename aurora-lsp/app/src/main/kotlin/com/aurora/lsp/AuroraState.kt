package com.aurora.lsp

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for the UI.
 *
 * Reads go straight through Settings.Global (no root needed); writes go through `su` because the
 * module's settings live in the global namespace.
 */
class AuroraState(private val context: Context) {

    var enabled by mutableStateOf(false)
        private set
    var preset by mutableStateOf("flat")
        private set
    var strength by mutableStateOf(100)
        private set
    var curve by mutableStateOf(FloatArray(AudioEq.BANDS))
        private set

    var backendState by mutableStateOf("")
        private set
    var backendMessage by mutableStateOf("")
        private set
    var sessions by mutableStateOf("")
        private set
    var applied by mutableStateOf("")
        private set

    var loaded by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The user's own saved curve, independent of whichever preset is currently active. */
    var savedCurve by mutableStateOf<FloatArray?>(null)
        private set

    /** Recent engine state changes, oldest first. Shown on the diagnostics page. */
    var engineLog by mutableStateOf<List<String>>(emptyList())
        private set

    /** Presets the user saved, newest last. */
    var userPresets by mutableStateOf<List<UserPreset>>(emptyList())
        private set

    /** Package name to preset id. */
    var appMap by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Stereo widening, 0-8 dB. */
    var spread by mutableStateOf(0f)
        private set

    /** Perceived loudness boost in dB (LoudnessEnhancer). */
    var loudnessDb by mutableStateOf(0f)
        private set

    /** Ten pre-EQ bands on a 20 ms frame instead of six on 10 ms; costs about 10 ms of latency. */
    var precise by mutableStateOf(false)
        private set

    /** Gains the engine actually wrote, as reported back through aurora_backend_bands. */
    var actualBands by mutableStateOf<FloatArray?>(null)
        private set

    /** Band table the engine actually drives (device pack or fallback), or null for the default. */
    var engineBandHz by mutableStateOf<FloatArray?>(null)
        private set

    /** "pack:<name>", "fallback" or a combination, when adapted settings are in force. */
    var adapt by mutableStateOf("")
        private set

    /** Name of the device pack currently stored, read straight from Settings (no root needed). */
    var packName by mutableStateOf<String?>(null)
        private set

    /** What the module found when it probed this device at boot. */
    var device by mutableStateOf(DeviceProfile())
        private set

    val presetIndex: Int get() = Presets.indexOf(preset)

    /**
     * Settings.Global reads are binder round trips; doing eight of them on the main thread every
     * two seconds was enough to make page switches stutter. Read off the main thread, publish once.
     */
    suspend fun refresh() {
        apply(withContext(Dispatchers.IO) { readSnapshot() })
    }

    private class Snapshot(
        val enabled: String?,
        val preset: String?,
        val strength: String?,
        val state: String?,
        val message: String?,
        val sessions: String?,
        val applied: String?,
        val geq: String?,
        val geqSaved: String?,
        val bands: String?,
        val log: String?,
        val userPresetsRaw: String?,
        val appMapRaw: String?,
        val spreadRaw: String?,
        val loudnessRaw: String?,
        val preciseRaw: String?,
        val bandHzRaw: String?,
        val adaptRaw: String?,
        val packRaw: String?,
        val device: DeviceProfile
    )

    /** A preset the user saved and named. */
    data class UserPreset(val id: String, val name: String, val curve: FloatArray) {
        override fun equals(other: Any?): Boolean = other is UserPreset && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    private fun readSnapshot(): Snapshot {
        val resolver = context.contentResolver
        fun read(key: String): String? = try {
            Settings.Global.getString(resolver, key)
        } catch (t: Throwable) {
            null
        }
        val resolver2 = context.contentResolver
        return Snapshot(
            enabled = read(AuroraEngine.K_ENABLED),
            preset = read(AuroraEngine.K_PRESET),
            strength = read(AuroraEngine.K_STRENGTH),
            state = read(AuroraEngine.R_STATE),
            message = read(AuroraEngine.R_MESSAGE),
            sessions = read(AuroraEngine.R_SESSIONS),
            applied = read(AuroraEngine.R_APPLIED),
            geq = read(AuroraEngine.K_GEQ),
            geqSaved = read(AuroraEngine.K_GEQ_SAVED),
            bands = read(AuroraEngine.R_BANDS),
            log = read(AuroraEngine.R_LOG),
            userPresetsRaw = read(AuroraEngine.K_USER_PRESETS),
            appMapRaw = read(AuroraEngine.K_APP_MAP),
            spreadRaw = read(AuroraEngine.K_SPREAD),
            loudnessRaw = read(AuroraEngine.K_LOUDNESS),
            preciseRaw = read(AuroraEngine.K_PRECISE),
            bandHzRaw = read(AuroraEngine.R_BANDHZ),
            adaptRaw = read(AuroraEngine.R_ADAPT),
            packRaw = read(AuroraEngine.K_PACK),
            device = DeviceProfile.read(resolver2)
        )
    }

    private fun apply(raw: Snapshot) {
        enabled = raw.enabled == "1"
        raw.preset?.takeIf { it.isNotEmpty() && it != "null" }?.let { stored ->
            // An unknown key falls through to index 0 - the flat curve - which looks exactly like
            // "the module does nothing". The fallback stays (flat is the safe state); it just
            // stops being silent, so a typo is findable in the log.
            if (stored == "custom" || stored in Presets.KEYS) {
                preset = stored
            } else {
                preset = "flat"
                AuroraLog.append(context, "preset", "unknown key '" + stored + "', falling back to flat")
            }
        }
        raw.strength?.toIntOrNull()?.let { strength = it.coerceIn(0, 100) }
        backendState = raw.state.orEmpty().let { if (it == "null") "" else it }
        backendMessage = raw.message.orEmpty().let { if (it == "null") "" else it }
        sessions = raw.sessions.orEmpty().let { if (it == "null") "" else it }
        applied = raw.applied.orEmpty().let { if (it == "null") "" else it }

        val stored = parseCurve(raw.geq)
        curve = stored ?: Presets.curve(Presets.indexOf(preset)).copyOf()
        if (stored != null) preset = "custom"
        savedCurve = parseCurve(raw.geqSaved)
        actualBands = parseCurve(raw.bands)
        engineLog = raw.log.orEmpty()
            .let { if (it == "null") "" else it }
            .split('\n')
            .filter { it.isNotBlank() }
            .takeLast(50)
        userPresets = parsePresets(raw.userPresetsRaw)
        appMap = parseAppMap(raw.appMapRaw)
        spread = (raw.spreadRaw?.toIntOrNull() ?: 0) / 10f
        loudnessDb = ((raw.loudnessRaw?.toIntOrNull() ?: 0) / 100f)
        precise = raw.preciseRaw?.toIntOrNull() == 1
        engineBandHz = parseHz(raw.bandHzRaw)
        adapt = raw.adaptRaw.orEmpty().let { if (it == "null") "" else it }
        packName = raw.packRaw
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { runCatching { JSONObject(it).optString("name") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        device = raw.device
        loaded = true
    }

    private fun parseHz(raw: String?): FloatArray? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val parts = raw.split(",")
        if (parts.size != AudioEq.BANDS) return null
        return FloatArray(AudioEq.BANDS) { parts[it].trim().toFloatOrNull() ?: return null }
    }

    /**
     * Stores a device pack fetched from the release host. The engine picks it up within one
     * observer-triggered tick. Null clears the pack.
     */
    suspend fun applyDevicePack(json: String?): Boolean {
        val command = if (json.isNullOrBlank()) {
            "settings delete global " + AuroraEngine.K_PACK + " && echo AURORA_OK"
        } else {
            "settings put global " + AuroraEngine.K_PACK + " '" +
                    json.replace("'", "'\\''") + "' && echo AURORA_OK"
        }
        if (command == lastCommand) return true
        val output = withContext(Dispatchers.IO) { runRoot(command) }
        if (!output.contains("AURORA_OK")) {
            AuroraLog.append(context, "pack", "write failed: " + output.take(160))
            return false
        }
        lastCommand = command
        refresh()
        return true
    }

    /** The raw pack JSON currently stored, readable without root. */
    fun currentPackRaw(): String? = try {
        Settings.Global.getString(context.contentResolver, AuroraEngine.K_PACK)
    } catch (t: Throwable) {
        null
    }

    suspend fun update(
        newEnabled: Boolean = enabled,
        newPreset: String = preset,
        newStrength: Int = strength,
        newCurve: FloatArray? = null
    ) {
        saving = true
        error = null
        // The preset key ends up inside a shell command; anything not in the table is rejected
        // here rather than escaped later.
        val safePreset = if (newPreset == "custom" || newPreset in Presets.KEYS) newPreset else "flat"
        // "custom" is not in KEYS, so looking it up would land on index 0 - the flat curve. A
        // call that only meant to change the switch or the strength would then overwrite the
        // user's own curve with zeros. Keep the live curve in that case.
        val targetCurve = newCurve
            ?: if (safePreset == "custom") curve.copyOf()
            else Presets.curve(Presets.indexOf(safePreset)).copyOf()
        val isCustom = newCurve != null || safePreset == "custom"

        val steps = ArrayList<Pair<String, String>>()
        steps += "preset" to "settings put global " + AuroraEngine.K_PRESET + " " +
                (if (isCustom) "flat" else safePreset)
        steps += "strength" to "settings put global " + AuroraEngine.K_STRENGTH + " " + newStrength
        if (isCustom) {
            val encoded = targetCurve.joinToString(",") { (it * 10).toInt().toString() }
            steps += "curve" to
                    "settings put global " + AuroraEngine.K_GEQ + " " + encoded
            // Keep a copy in the saved slot, so switching presets later cannot lose it. Only a
            // hand-edited curve reaches this branch; restoring a preset writes no custom curve at
            // all, so the saved slot is never replaced by one.
            steps += "saved" to
                    "settings put global " + AuroraEngine.K_GEQ_SAVED + " " + encoded
        } else {
            steps += "curve" to "settings delete global " + AuroraEngine.K_GEQ
        }
        // The switch goes last. A shell that dies mid-batch then leaves the module either off or
        // on with a whole curve - not on, pointed at a preset, with last week's gains underneath.
        steps += "enabled" to
                "settings put global " + AuroraEngine.K_ENABLED + " " + (if (newEnabled) 1 else 0)

        val command = steps.joinToString("\n") { it.second }
        if (command == lastCommand) {
            // Same command, same effect. A double tap no longer forks su twice.
            saving = false
            return
        }
        val failed = withContext(Dispatchers.IO) { writeSteps(steps) }
        saving = false
        if (failed != null) {
            AuroraLog.append(context, "save", "failed at " + failed)
            error = if (failed == "su") {
                "保存失败。请确认已给 AURORA 授予 Root 权限后重试。当前设置没有丢失。"
            } else {
                "保存失败（$failed 这一步）。当前设置没有丢失。"
            }
            return
        }
        lastCommand = command
        enabled = newEnabled
        preset = if (isCustom) "custom" else safePreset
        strength = newStrength
        curve = targetCurve
        if (isCustom) savedCurve = targetCurve
        refresh()
    }

    /** Re-applies the curve the user saved earlier, after they switched to a preset. */
    suspend fun restoreSaved() {
        val saved = savedCurve ?: return
        update(newCurve = saved)
    }

    /** Writes the advanced DSP parameters through su. */
    suspend fun updateAdvanced(
        spread: Float = this.spread,
        loudnessDb: Float = this.loudnessDb,
        precise: Boolean = this.precise
    ) {
        saving = true
        error = null
        val cmd = buildString {
            append("settings put global ").append(AuroraEngine.K_SPREAD).append(' ')
            append((spread * 10).toInt())
            append(" && settings put global ").append(AuroraEngine.K_LOUDNESS).append(' ')
            append((loudnessDb * 100).toInt())
            append(" && settings put global ").append(AuroraEngine.K_PRECISE).append(' ')
            append(if (precise) 1 else 0)
            append(" && echo AURORA_OK")
        }
        val output = withContext(Dispatchers.IO) { runRoot(cmd) }
        saving = false
        if (!output.contains("AURORA_OK")) {
            error = "保存失败。请确认已给 AURORA 授予 Root 权限。"
            return
        }
        lastCommand = cmd
        this.spread = spread
        this.loudnessDb = loudnessDb
        this.precise = precise
        refresh()
    }

    suspend fun selfTest() {
        val token = System.currentTimeMillis() / 1000
        withContext(Dispatchers.IO) {
            runRoot("settings put global " + AuroraEngine.K_SELFTEST + " " + token)
        }
    }

    private fun parseCurve(raw: String?): FloatArray? {
        if (raw == null || raw.isEmpty() || raw == "null") return null
        val parts = raw.split(",")
        if (parts.size != AudioEq.BANDS) return null
        return FloatArray(AudioEq.BANDS) { parts[it].trim().toIntOrNull()?.div(10f) ?: return null }
    }

    private fun parsePresets(raw: String?): List<UserPreset> {
        if (raw.isNullOrEmpty() || raw == "null") return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val curve = item.optJSONArray("curve") ?: return@mapNotNull null
                if (id.isEmpty() || curve.length() != AudioEq.BANDS) return@mapNotNull null
                UserPreset(
                    id = id,
                    name = item.optString("name").ifBlank { "未命名" },
                    curve = FloatArray(AudioEq.BANDS) {
                        (curve.optDouble(it, 0.0) / 10.0).toFloat()
                    }
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun parseAppMap(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty() || raw == "null") return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = mutableMapOf<String, String>()
            obj.keys().forEach { key ->
                val value = obj.optString(key)
                if (value.isNotEmpty() && value != "null") out[key] = value
            }
            out
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    private fun presetsToJson(presets: List<UserPreset>): String {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(JSONObject().apply {
                put("id", preset.id)
                put("name", preset.name)
                put("curve", JSONArray(preset.curve.map { (it * 10).toInt() }))
            })
        }
        return array.toString()
    }

    private fun appMapToJson(map: Map<String, String>): String {
        val json = JSONObject()
        map.forEach { (key, value) -> json.put(key, value) }
        return json.toString()
    }

    /** Saves the given curve under a new name; the newest preset lands at the end of the list. */
    suspend fun savePreset(name: String, curve: FloatArray) {
        val id = "u" + System.currentTimeMillis()
        val updated = userPresets + UserPreset(id, name.trim().ifBlank { "我的预设" }, curve)
        commit(AuroraEngine.K_USER_PRESETS, presetsToJson(updated))
    }

    suspend fun renamePreset(id: String, name: String) {
        val updated = userPresets.map {
            if (it.id == id) UserPreset(it.id, name.trim().ifBlank { it.name }, it.curve) else it
        }
        commit(AuroraEngine.K_USER_PRESETS, presetsToJson(updated))
    }

    suspend fun deletePreset(id: String) {
        commit(AuroraEngine.K_USER_PRESETS, presetsToJson(userPresets.filterNot { it.id == id }))
        // Assignments pointing at the removed preset would silently fall back; clear them now.
        val cleaned = appMap.filterNot { it.value == id }
        if (cleaned.size != appMap.size) {
            commit(AuroraEngine.K_APP_MAP, appMapToJson(cleaned))
        }
    }

    suspend fun assignApp(packageName: String, presetId: String?) {
        val updated = appMap.toMutableMap()
        if (presetId == null) updated.remove(packageName) else updated[packageName] = presetId
        commit(AuroraEngine.K_APP_MAP, appMapToJson(updated))
    }

    /** Writes one JSON key through su, then reloads so the UI reflects what actually landed. */
    private suspend fun commit(key: String, json: String) {
        val command = "settings put global $key '" + json.replace("'", "'\\''") + "' && echo AURORA_OK"
        if (command == lastCommand) return
        val output = withContext(Dispatchers.IO) { runRoot(command) }
        if (!output.contains("AURORA_OK")) {
            AuroraLog.append(context, "preset", "write failed: " + output.take(160))
            error = "保存失败。请确认已给 AURORA 授予 Root 权限。"
            return
        }
        lastCommand = command
        error = null
        refresh()
    }

    /** Last shell command that succeeded, so tapping the same thing twice forks su once. */
    private var lastCommand: String = ""

    /**
     * Runs the settings writes one after another in a single su shell and names the step that blew
     * up. Returns null when every step landed, "su" when the shell never ran at all (root missing or
     * denied), or the step name otherwise - the caller shows that name, so a half applied batch is
     * something the user can report instead of a generic failure.
     */
    private fun writeSteps(steps: List<Pair<String, String>>): String? {
        val script = buildString {
            steps.forEach { (name, command) ->
                append(command)
                append(" || { echo 'AURORA_FAIL ")
                append(name)
                append("'; exit 1; }\n")
            }
            append("echo AURORA_OK\n")
        }
        val output = runRoot(script)
        if (output.contains("AURORA_OK")) return null
        Regex("AURORA_FAIL (\\w+)").find(output)?.let { return it.groupValues[1] }
        // No step spoke up, so the shell itself probably never started. Ask once, cheaply, before
        // blaming root - otherwise a timeout on a slow su would be reported as missing permission.
        return if (runRoot("echo AURORA_OK").contains("AURORA_OK")) "unknown" else "su"
    }

    private fun runRoot(command: String): String {
        val output = StringBuilder()
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val running = process
            val reader = Thread {
                BufferedReader(InputStreamReader(running.inputStream)).use { input ->
                    var line: String? = input.readLine()
                    while (line != null && output.length < 4000) {
                        synchronized(output) { output.append(line).append('\n') }
                        line = input.readLine()
                    }
                }
            }
            reader.start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return "timeout"
            }
            reader.join(600)
            output.toString()
        } catch (t: Throwable) {
            process?.destroyForcibly()
            "error:${t.javaClass.simpleName}"
        }
    }
}