package com.aurora.lsp

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Sends the device profile home when the module cannot bring up the audio chain here.
 *
 * One report per (profile stage, app version) so a broken build cannot spam the endpoint every
 * launch. The payload is facts only: what the probe found, never user content.
 */
object Feedback {

    const val ENDPOINT = "https://lingluandf.online/feedback/device"
    const val REPORT_ENDPOINT = "https://lingluandf.online/feedback/report"

    /**
     * What actually happened, so the UI can stop blaming the network for everything.
     *
     * <p>A 405 from an nginx that routed the request to the site instead of the feedback service
     * used to surface as "请检查网络" - the user's network was fine the whole time.
     */
    sealed interface SendResult {
        object Sent : SendResult

        /** Reached the server; it answered with an error code. */
        data class ServerError(val code: Int) : SendResult

        /** Never reached the server: DNS, TLS, timeout, socket. */
        data class NetworkError(val reason: String) : SendResult
    }

    private const val PREF_NAME = "aurora_ui"
    private const val KEY_REPORTED = "reported_profile"
    private const val KEY_ANON = "anon_id"

    /** Stable but anonymous: only used to tell two reports from the same install apart. */
    private fun anonId(prefs: SharedPreferences): String {
        prefs.getString(KEY_ANON, null)?.let { return it }
        val value = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_ANON, value).apply()
        return value
    }

    /** Manual report from the feedback form. Logs ride along only when the user keeps the switch on. */
    suspend fun reportIssue(
        context: Context,
        problem: String,
        text: String,
        withLogs: Boolean,
        profile: DeviceProfile,
        versionCode: Int,
        versionName: String,
        prefs: SharedPreferences
    ): SendResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("kind", problem)
            put("text", text.take(2000))
            put("model", profile.model)
            put("sdk", profile.sdk)
            put("miui", profile.miui)
            put("versionCode", versionCode)
            put("versionName", versionName)
            put("anonId", anonId(prefs))
            if (withLogs) put("logs", AuroraLog.dump(context).take(100_000))
        }

        try {
            val connection = URL(REPORT_ENDPOINT).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("User-Agent", "AURORA-Android")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            try {
                val code = connection.responseCode
                if (code in 200..299) SendResult.Sent else SendResult.ServerError(code)
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            SendResult.NetworkError(t.javaClass.simpleName)
        }
    }

    /** Returns true when a report was actually sent. */
    suspend fun reportDevice(
        context: Context,
        profile: DeviceProfile,
        versionCode: Int,
        versionName: String,
        prefs: SharedPreferences,
        force: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        // Automatic reports stay deduplicated; a report the user asked for always goes out,
        // because "I pressed the button" is new information even when the profile has not changed.
        val stamp = "${profile.stage}:$versionCode"
        if (!force && prefs.getString(KEY_REPORTED, null) == stamp) return@withContext false

        val body = JSONObject().apply {
            put("kind", "device_profile")
            put("stage", profile.stage)
            put("stageLabel", profile.stageLabel())
            put("reason", profile.reason)
            put("model", profile.model)
            put("soc", profile.soc)
            put("sdk", profile.sdk)
            put("miui", profile.miui)
            put("effects", profile.effects)
            put("outputs", profile.outputs)
            put("route", profile.route)
            put("versionCode", versionCode)
            put("versionName", versionName)
            put("probedAt", profile.probedAt)
            put("anonId", anonId(prefs))
            // Whether the engine could actually write is the other half of the story: a device
            // can pass the probe and still reject every band, which used to be invisible here.
            put("backend", profile.backendState)
            put("backendMessage", profile.backendMessage)
            put("bandHz", profile.bandHz)
            // Which stages exist in the chain, not just whether the curve landed.
            put("stages", profile.stages)
        }.toString()

        try {
            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("User-Agent", "AURORA-Android")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            try {
                val ok = connection.responseCode in 200..299
                if (ok) prefs.edit().putString(KEY_REPORTED, stamp).apply()
                ok
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            false
        }
    }
}