package com.aurora.lsp

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * State for the community page: the feed, my likes, and the anonymous identity.
 *
 * <p>Identity is deliberately invisible: the same install-level anonId the feedback reporter uses
 * stands in for an account. The recovery key is the only durable secret; it is shown once when the
 * server first creates the account, and whoever holds it can reclaim everything on a new install.
 */
class CommunityState(
    private val context: Context,
    private val prefs: SharedPreferences
) {

    enum class FeedStatus { Idle, Loading, Ready, Empty, Offline }

    var feed by mutableStateOf<List<Community.Item>>(emptyList())
        private set
    var feedStatus by mutableStateOf(FeedStatus.Idle)
        private set
    var nextCursor by mutableStateOf<Int?>(null)
        private set
    var loadingMore by mutableStateOf(false)
        private set

    var myLikes by mutableStateOf<List<Community.Item>>(emptyList())
        private set
    var likesStatus by mutableStateOf(FeedStatus.Idle)
        private set

    var nickname by mutableStateOf("")
        private set
    var avatar by mutableStateOf("eq")
        private set

    /** Shown once, right after the server creates the identity: "save this". */
    var freshRecoveryKey by mutableStateOf<String?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** Same key and generation as Feedback: one identity per install, shared by both features. */
    val anonId: String
        get() = synchronized(prefs) {
            prefs.getString(KEY_ANON, null)?.let { return it }
            val value = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_ANON, value).apply()
            value
        }

    init {
        nickname = prefs.getString(KEY_NICKNAME, "").orEmpty()
        avatar = prefs.getString(KEY_AVATAR, "eq").orEmpty().ifBlank { "eq" }
    }

    suspend fun refreshFeed() {
        if (feedStatus == FeedStatus.Loading) return
        feedStatus = FeedStatus.Loading
        error = null
        when (val result = Community.feed(anonId)) {
            is Community.Result.Ok -> {
                feed = result.value.items
                nextCursor = result.value.nextCursor
                feedStatus = if (result.value.items.isEmpty()) FeedStatus.Empty else FeedStatus.Ready
            }
            is Community.Result.Denied -> {
                feedStatus = if (feed.isEmpty()) FeedStatus.Offline else FeedStatus.Ready
                error = "服务器拒绝了请求（" + result.code + "），稍后再试。"
            }
            is Community.Result.Failed -> {
                feedStatus = if (feed.isEmpty()) FeedStatus.Offline else FeedStatus.Ready
                error = null
            }
        }
    }

    suspend fun loadMore() {
        val cursor = nextCursor ?: return
        if (loadingMore) return
        loadingMore = true
        when (val result = Community.feed(anonId, cursor)) {
            is Community.Result.Ok -> {
                val known = feed.map { it.id }.toHashSet()
                feed = feed + result.value.items.filter { it.id !in known }
                nextCursor = result.value.nextCursor
            }
            else -> Unit
        }
        loadingMore = false
    }

    suspend fun refreshLikes() {
        likesStatus = FeedStatus.Loading
        when (val result = Community.likes(anonId)) {
            is Community.Result.Ok -> {
                myLikes = result.value.items
                likesStatus = if (result.value.items.isEmpty()) FeedStatus.Empty else FeedStatus.Ready
            }
            is Community.Result.Denied -> likesStatus = FeedStatus.Ready
            is Community.Result.Failed -> likesStatus = FeedStatus.Offline
        }
    }

    suspend fun toggleLike(item: Community.Item): Boolean = withContext(Dispatchers.IO) {
        val target = !item.liked
        when (val result = Community.like(item.id, anonId, target)) {
            is Community.Result.Ok -> {
                // A like can be the first thing a person does; if that created the account, show
                // the key right away instead of losing it.
                result.value?.let { freshRecoveryKey = it }
                fun patch(list: List<Community.Item>) = list.map {
                    if (it.id == item.id) {
                        it.copy(liked = target, likes = (it.likes + if (target) 1 else -1).coerceAtLeast(0))
                    } else it
                }
                feed = patch(feed)
                myLikes = if (target) myLikes else myLikes.filterNot { it.id == item.id }
                true
            }
            else -> false
        }
    }

    suspend fun publish(
        name: String,
        curve: FloatArray,
        model: String,
        deviceLabel: String,
        bandHz: String
    ): Community.Result<Community.PublishResult> {
        error = null
        val result = Community.publish(anonId, name, curve, model, deviceLabel, bandHz)
        if (result is Community.Result.Ok) {
            result.value.recoveryKey?.let { freshRecoveryKey = it }
        } else if (result is Community.Result.Denied) {
            error = "服务器拒绝了这次发布（" + result.code + "）。"
        } else if (result is Community.Result.Failed) {
            error = "连不上社区服务器，等网络正常再试。"
        }
        return result
    }

    suspend fun addComment(presetId: Int, body: String): Boolean {
        return when (val result = Community.comment(presetId, anonId, body)) {
            is Community.Result.Ok -> {
                result.value?.let { freshRecoveryKey = it }
                true
            }
            else -> false
        }
    }

    /** Pushes nickname and avatar to the server and caches them locally for offline display. */
    suspend fun updateProfile(newNickname: String, newAvatar: String): Boolean {
        val result = Community.account(anonId, newNickname.ifBlank { null }, newAvatar)
        return when (result) {
            is Community.Result.Ok -> {
                nickname = result.value.nickname.ifBlank { newNickname }
                avatar = result.value.avatar.ifBlank { newAvatar }
                prefs.edit()
                    .putString(KEY_NICKNAME, nickname)
                    .putString(KEY_AVATAR, avatar)
                    .apply()
                error = null
                true
            }
            is Community.Result.Denied -> {
                error = "服务器拒绝了这次修改（" + result.code + "）。"
                false
            }
            is Community.Result.Failed -> {
                error = "连不上社区服务器，身份信息没有保存。"
                false
            }
        }
    }

    /** Fetches the profile the server has for this anonId (no-op when the account is new). */
    suspend fun refreshProfile() {
        when (val result = Community.account(anonId)) {
            is Community.Result.Ok -> {
                nickname = result.value.nickname.ifBlank { nickname }
                avatar = result.value.avatar.ifBlank { avatar }
                prefs.edit()
                    .putString(KEY_NICKNAME, nickname)
                    .putString(KEY_AVATAR, avatar)
                    .apply()
            }
            else -> Unit
        }
    }

    suspend fun recoverIdentity(key: String): Boolean {
        return when (val result = Community.recover(key.trim(), anonId)) {
            is Community.Result.Ok -> {
                nickname = result.value.nickname
                avatar = result.value.avatar
                prefs.edit()
                    .putString(KEY_NICKNAME, nickname)
                    .putString(KEY_AVATAR, avatar)
                    .apply()
                error = null
                refreshFeed()
                true
            }
            is Community.Result.Denied -> {
                error = "找回码不对，或者这个身份已经不存在了。"
                false
            }
            is Community.Result.Failed -> {
                error = "连不上社区服务器，稍后再试。"
                false
            }
        }
    }

    suspend fun rotateKey(): String? {
        return when (val result = Community.rotateKey(anonId)) {
            is Community.Result.Ok -> result.value.ifBlank { null }
            else -> null
        }
    }

    fun clearFreshKey() {
        freshRecoveryKey = null
    }

    fun clearError() {
        error = null
    }

    companion object {
        private const val KEY_ANON = "anonId"
        private const val KEY_NICKNAME = "community_nickname"
        private const val KEY_AVATAR = "community_avatar"
    }
}