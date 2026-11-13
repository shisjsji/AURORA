package com.aurora.lsp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the community service (preset sharing, likes, comments).
 *
 * <p>Same shape as Feedback and Update: HttpURLConnection on IO, sealed results that keep "the
 * server refused" apart from "the network never got there" - the community page has to say which
 * one it is, because only one of them has anything to retry.
 */
object Community {

    const val BASE = "https://lingluandf.online/community"

    data class Item(
        val id: Int,
        val name: String,
        val curve: FloatArray,
        val model: String,
        val deviceLabel: String,
        val bandHz: String,
        val nickname: String,
        val avatar: String,
        val likes: Int,
        val comments: Int,
        val liked: Boolean,
        val createdAt: Long
    )

    data class Comment(
        val id: Int,
        val body: String,
        val nickname: String,
        val avatar: String,
        val createdAt: Long
    )

    data class Feed(val items: List<Item>, val nextCursor: Int?)

    data class Profile(val nickname: String, val avatar: String)

    data class PublishResult(val id: Int, val recoveryKey: String?)

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>

        /** Reached the server; it said no. */
        data class Denied(val code: Int) : Result<Nothing>

        /** Never reached the server: DNS, TLS, timeout, socket. */
        data class Failed(val reason: String) : Result<Nothing>
    }

    private fun bodyOf(connection: HttpURLConnection): String =
        connection.inputStream.bufferedReader().use { it.readText() }

    private fun call(
        path: String,
        method: String = "GET",
        payload: JSONObject? = null
    ): Result<JSONObject> = try {
        val connection = URL(BASE + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 8000
        connection.readTimeout = 12000
        connection.setRequestProperty("User-Agent", "AURORA-Android")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (payload != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                Result.Denied(code)
            } else {
                val text = bodyOf(connection)
                Result.Ok(JSONObject(text))
            }
        } finally {
            connection.disconnect()
        }
    } catch (t: Throwable) {
        Result.Failed(t.javaClass.simpleName)
    }

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
            nickname = json.optString("nickname"),
            avatar = json.optString("avatar"),
            likes = json.optInt("likes"),
            comments = json.optInt("comments"),
            liked = json.optBoolean("liked"),
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

    suspend fun feed(anonId: String, cursor: Int = 0): Result<Feed> = withContext(Dispatchers.IO) {
        val suffix = if (cursor > 0) "&cursor=$cursor" else ""
        call("/presets?anonId=$anonId$suffix").map(::parseFeed)
    }

    suspend fun likes(anonId: String): Result<Feed> = withContext(Dispatchers.IO) {
        call("/likes?anonId=$anonId").map(::parseFeed)
    }

    suspend fun detail(id: Int, anonId: String): Result<Pair<Item, List<Comment>>> =
        withContext(Dispatchers.IO) {
            when (val result = call("/presets/$id?anonId=$anonId")) {
                is Result.Ok -> {
                    val item = result.value.optJSONObject("item")?.let(::parseItem)
                        ?: return@withContext Result.Failed("bad payload")
                    val comments = result.value.optJSONObject("item")
                        ?.optJSONArray("commentList")
                        ?.let { array ->
                            (0 until array.length()).mapNotNull { index ->
                                array.optJSONObject(index)?.let { c ->
                                    Comment(
                                        c.optInt("id"),
                                        c.optString("body"),
                                        c.optString("nickname"),
                                        c.optString("avatar"),
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
        anonId: String,
        name: String,
        curve: FloatArray,
        model: String,
        deviceLabel: String,
        bandHz: String
    ): Result<PublishResult> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("anonId", anonId)
            put("name", name)
            put("curve", JSONArray(curve.map { Math.round(it * 10f) }))
            put("model", model)
            put("deviceLabel", deviceLabel)
            put("bandHz", bandHz)
        }
        call("/presets", "POST", payload).map { json ->
            PublishResult(
                id = json.optInt("id"),
                recoveryKey = json.optString("recoveryKey").takeIf { it.isNotBlank() }
            )
        }
    }

    /** Like or unlike. The recovery key rides back when this call created the account. */
    suspend fun like(id: Int, anonId: String, on: Boolean): Result<String?> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("anonId", anonId)
            call("/presets/$id/like", if (on) "POST" else "DELETE", payload).map { json ->
                json.optString("recoveryKey").takeIf { it.isNotBlank() }
            }
        }

    suspend fun comment(id: Int, anonId: String, body: String): Result<String?> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("anonId", anonId)
                put("body", body)
            }
            call("/presets/$id/comments", "POST", payload).map { json ->
                json.optString("recoveryKey").takeIf { it.isNotBlank() }
            }
        }

    suspend fun account(
        anonId: String,
        nickname: String? = null,
        avatar: String? = null
    ): Result<Profile> = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("anonId", anonId)
        if (nickname != null) payload.put("nickname", nickname)
        if (avatar != null) payload.put("avatar", avatar)
        call("/account", "POST", payload).map { json ->
            val profile = json.optJSONObject("profile")
            Profile(
                nickname = profile?.optString("nickname").orEmpty(),
                avatar = profile?.optString("avatar").orEmpty()
            )
        }
    }

    suspend fun recover(recoveryKey: String, anonId: String): Result<Profile> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("recoveryKey", recoveryKey)
                put("anonId", anonId)
            }
            call("/account/recover", "POST", payload).map { json ->
                val profile = json.optJSONObject("profile")
                Profile(
                    nickname = profile?.optString("nickname").orEmpty(),
                    avatar = profile?.optString("avatar").orEmpty()
                )
            }
        }

    suspend fun rotateKey(anonId: String): Result<String> = withContext(Dispatchers.IO) {
        call("/account/rotate", "POST", JSONObject().put("anonId", anonId)).map { json ->
            json.optString("recoveryKey")
        }
    }

    private fun <T> Result<JSONObject>.map(transform: (JSONObject) -> T): Result<T> =
        when (this) {
            is Result.Ok -> Result.Ok(transform(value))
            is Result.Denied -> this
            is Result.Failed -> this
        }
}