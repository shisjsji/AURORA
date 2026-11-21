package com.aurora.lsp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the community service: email login, preset sharing, likes, comments.
 *
 * <p>Reads are public; every write carries the login token. Results keep "the server refused" apart
 * from "the network never got there", and a refusal carries the server's reason code so the UI can
 * say "you already published this" instead of a bare status number.
 */
object Community {

    const val BASE = "https://lingluandf.online/community"

    /** Uploaded avatars are served from here; the stored value is "img:<file>". */
    const val AVATARS = BASE + "/avatars/"

    data class Item(
        val id: Int,
        val name: String,
        val curve: FloatArray,
        val model: String,
        val deviceLabel: String,
        val bandHz: String,
        /** Preset key the curve was published from ("harman", "custom", ...), "" when unknown. */
        val preset: String,
        /** Strength the curve was published at, 100 when unknown. */
        val strength: Int,
        val nickname: String,
        val avatar: String,
        val likes: Int,
        val comments: Int,
        val liked: Boolean,
        /** True when this is the viewer's own preset. Always false while logged out. */
        val mine: Boolean,
        /** Account that published it, 0 when the payload did not carry one. */
        val authorId: Int,
        val createdAt: Long
    )

    data class Comment(
        val id: Int,
        val body: String,
        val nickname: String,
        val avatar: String,
        val authorId: Int,
        val mine: Boolean,
        val createdAt: Long
    )

    data class Feed(val items: List<Item>, val nextCursor: Int?)

    data class Profile(
        val nickname: String,
        val avatar: String,
        val email: String,
        val role: String = "user"
    )

    data class Session(val token: String, val profile: Profile)

    data class CodeSent(val ttlSeconds: Int, val resendSeconds: Int)

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>

        /** Reached the server; it said no. [error] is the machine-readable reason, if any. */
        data class Denied(
            val code: Int,
            val error: String? = null,
            val message: String? = null
        ) : Result<Nothing>

        /** Never reached the server: DNS, TLS, timeout, socket. */
        data class Failed(val reason: String) : Result<Nothing>
    }

    private fun call(
        path: String,
        method: String = "GET",
        payload: JSONObject? = null,
        token: String? = null
    ): Result<JSONObject> = try {
        val connection = URL(BASE + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 8000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "AURORA-Android")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token)
        if (payload != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                // The reason lives in the error body; a bare 409 helps nobody.
                val text = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (t: Throwable) {
                    null
                }
                val parsed = text?.let { runCatching { JSONObject(it) }.getOrNull() }
                Result.Denied(
                    code = code,
                    error = parsed?.optString("error")?.takeIf { it.isNotBlank() },
                    message = parsed?.optString("message")?.takeIf { it.isNotBlank() }
                )
            } else {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                Result.Ok(JSONObject(text))
            }
        } finally {
            connection.disconnect()
        }
    } catch (t: Throwable) {
        Result.Failed(t.javaClass.simpleName)
    }

    private fun parseProfile(json: JSONObject?): Profile = Profile(
        nickname = json?.optString("nickname").orEmpty(),
        avatar = json?.optString("avatar").orEmpty(),
        email = json?.optString("email").orEmpty(),
        role = json?.optString("role").orEmpty().ifBlank { "user" }
    )

    private fun parseItem(json: JSONObject): Item? {
        val curveRaw = json.optString("curve", "")
        val parts = curveRaw.split(',').mapNotNull { it.trim().toFloatOrNull() }
        if (parts.size != AudioEq.BANDS) return null
        return Item(
            id = json.optInt("id"),
            name = json.optString("name"),
            curve = FloatArray(AudioEq.BANDS) { parts[it] / 10f },
            model = json.optString("model"),
            deviceLabel = json.optString("deviceLabel"),
            bandHz = json.optString("bandHz"),
            preset = json.optString("preset"),
            strength = json.optInt("strength", 100),
            nickname = json.optString("nickname"),
            avatar = json.optString("avatar"),
            likes = json.optInt("likes"),
            comments = json.optInt("comments"),
            liked = json.optBoolean("liked"),
            mine = json.optBoolean("mine"),
            authorId = json.optInt("authorId"),
                createdAt = json.optLong("createdAt")
        )
    }

    private fun parseFeed(json: JSONObject): Feed {
        val array = json.optJSONArray("items") ?: JSONArray()
        val items = (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::parseItem)
        }
        val cursor = if (json.isNull("nextCursor")) null else json.optInt("nextCursor").takeIf { it > 0 }
        return Feed(items, cursor)
    }

    // ---- 登录 ----

    suspend fun requestCode(email: String): Result<CodeSent> = withContext(Dispatchers.IO) {
        call("/auth/code", "POST", JSONObject().put("email", email)).map { json ->
            CodeSent(
                ttlSeconds = json.optInt("ttl", 600),
                resendSeconds = json.optInt("resendIn", 60)
            )
        }
    }

    /**
     * Exchanges a code for a session. [anonId] is the anonymous handle this install used before
     * accounts existed: the server uses it to move that content onto the account, so a first login
     * does not orphan anything the device published earlier.
     */
    suspend fun verify(email: String, code: String, anonId: String?): Result<Session> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("email", email)
                put("code", code)
                if (!anonId.isNullOrBlank()) put("anonId", anonId)
            }
            call("/auth/verify", "POST", payload).map { json ->
                Session(
                    token = json.optString("token"),
                    profile = parseProfile(json.optJSONObject("profile"))
                )
            }
        }

    suspend fun me(token: String): Result<Profile> = withContext(Dispatchers.IO) {
        call("/me", token = token).map { json -> parseProfile(json.optJSONObject("profile")) }
    }

    suspend fun updateProfile(token: String, nickname: String?, avatar: String?): Result<Profile> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
            if (nickname != null) payload.put("nickname", nickname)
            if (avatar != null) payload.put("avatar", avatar)
            call("/me", "PATCH", payload, token).map { json ->
                parseProfile(json.optJSONObject("profile"))
            }
        }

    suspend fun logout(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        call("/auth/logout", "POST", JSONObject(), token).map { }
    }

    /** Deletes the account and everything under it. The email is free again afterwards. */
    suspend fun deleteAccount(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        call("/me", "DELETE", null, token).map { }
    }

    // ---- 内容 ----

    suspend fun feed(token: String?, cursor: Int = 0): Result<Feed> = withContext(Dispatchers.IO) {
        val suffix = if (cursor > 0) "?cursor=$cursor" else ""
        call("/presets" + suffix, token = token).map(::parseFeed)
    }

    suspend fun mine(token: String): Result<Feed> = withContext(Dispatchers.IO) {
        call("/presets?mine=1", token = token).map(::parseFeed)
    }

    suspend fun likes(token: String): Result<Feed> = withContext(Dispatchers.IO) {
        call("/likes", token = token).map(::parseFeed)
    }

    suspend fun detail(id: Int, token: String?): Result<Pair<Item, List<Comment>>> =
        withContext(Dispatchers.IO) {
            when (val result = call("/presets/$id", token = token)) {
                is Result.Ok -> {
                    val itemJson = result.value.optJSONObject("item")
                    val item = itemJson?.let(::parseItem)
                        ?: return@withContext Result.Failed("bad payload")
                    val comments = itemJson.optJSONArray("commentList")?.let { array ->
                        (0 until array.length()).mapNotNull { index ->
                            array.optJSONObject(index)?.let { c ->
                                Comment(
                                    c.optInt("id"),
                                    c.optString("body"),
                                    c.optString("nickname"),
                                    c.optString("avatar"),
                                    c.optInt("authorId"),
                                    c.optBoolean("mine"),
                                    c.optLong("createdAt")
                                )
                            }
                        }
                    } ?: emptyList()
                    Result.Ok(item to comments)
                }
                is Result.Denied -> result
                is Result.Failed -> result
            }
        }

    suspend fun publish(
        token: String,
        name: String,
        curve: FloatArray,
        model: String,
        deviceLabel: String,
        bandHz: String,
        preset: String,
        strength: Int
    ): Result<Int> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("name", name)
            put("curve", JSONArray(curve.map { Math.round(it * 10f) }))
            put("model", model)
            put("deviceLabel", deviceLabel)
            put("bandHz", bandHz)
            put("preset", preset)
            put("strength", strength.coerceIn(0, 100))
        }
        call("/presets", "POST", payload, token).map { json -> json.optInt("id") }
    }

    suspend fun deletePreset(token: String, id: Int): Result<Unit> = withContext(Dispatchers.IO) {
        call("/presets/$id", "DELETE", null, token).map { }
    }

    suspend fun like(id: Int, token: String, on: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            call("/presets/$id/like", if (on) "POST" else "DELETE", JSONObject(), token).map { }
        }

    suspend fun comment(id: Int, token: String, body: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            call("/presets/$id/comments", "POST", JSONObject().put("body", body), token).map { }
        }

    /** Uploads one already-cropped 512×512 JPEG. Returns the stored avatar key ("img:<file>"). */
    suspend fun uploadAvatar(token: String, jpeg: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        try {
            val connection = URL(BASE + "/me/avatar").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 25000
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", "AURORA-Android")
            connection.setRequestProperty("Content-Type", "image/jpeg")
            connection.setRequestProperty("Authorization", "Bearer " + token)
            connection.outputStream.use { it.write(jpeg) }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    Result.Denied(code, message = "头像上传失败（" + code + "）。")
                } else {
                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    Result.Ok(JSONObject(text).optString("avatar"))
                }
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            Result.Failed(t.javaClass.simpleName)
        }
    }

    suspend fun deleteComment(token: String, id: Int): Result<Unit> = withContext(Dispatchers.IO) {
        call("/comments/$id", "DELETE", null, token).map { }
    }

    // ---- 管理 ----

    data class AdminUser(
        val id: Int,
        val email: String,
        val nickname: String,
        val avatar: String,
        val role: String,
        val banned: Boolean,
        val banReason: String,
        val presets: Int,
        val comments: Int
    )

    suspend fun adminUsers(token: String, query: String): Result<List<AdminUser>> =
        withContext(Dispatchers.IO) {
            val suffix = if (query.isBlank()) "" else "?q=" + java.net.URLEncoder.encode(query, "UTF-8")
            call("/admin/users" + suffix, token = token).map { json ->
                val array = json.optJSONArray("items") ?: JSONArray()
                (0 until array.length()).mapNotNull { index ->
                    val row = array.optJSONObject(index) ?: return@mapNotNull null
                    AdminUser(
                        id = row.optInt("id"),
                        email = row.optString("email"),
                        nickname = row.optString("nickname"),
                        avatar = row.optString("avatar"),
                        role = row.optString("role"),
                        banned = row.optBoolean("banned"),
                        banReason = row.optString("banReason"),
                        presets = row.optInt("presets"),
                        comments = row.optInt("comments")
                    )
                }
            }
        }

    /** Every published preset, including the ones hidden from the feed. */
    suspend fun adminPresets(token: String): Result<List<Item>> = withContext(Dispatchers.IO) {
        call("/admin/presets", token = token).map { json ->
            val array = json.optJSONArray("items") ?: JSONArray()
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::parseItem)
            }
        }
    }

    suspend fun ban(token: String, accountId: Int, reason: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("accountId", accountId).put("reason", reason)
            call("/admin/ban", "POST", payload, token).map { }
        }

    suspend fun unban(token: String, accountId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        call("/admin/unban", "POST", JSONObject().put("accountId", accountId), token).map { }
    }

    suspend fun setRole(token: String, accountId: Int, role: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("accountId", accountId).put("role", role)
            call("/admin/role", "POST", payload, token).map { }
        }

    private fun <T> Result<JSONObject>.map(transform: (JSONObject) -> T): Result<T> =
        when (this) {
            is Result.Ok -> Result.Ok(transform(value))
            is Result.Denied -> this
            is Result.Failed -> this
        }
}