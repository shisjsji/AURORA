package com.aurora.lsp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * State for the community page: the feed, my published curves, my likes.
 *
 * <p>Browsing works signed out; everything that creates something needs the account in [auth].
 * The page is not the gatekeeper, though - every write carries a token and the server refuses
 * without one, so a stale screen cannot publish on its own.
 */
class CommunityState(val auth: AuthState) {

    enum class FeedStatus { Idle, Loading, Ready, Empty, Offline }

    var feed by mutableStateOf<List<Community.Item>>(emptyList())
        private set
    var feedStatus by mutableStateOf(FeedStatus.Idle)
        private set
    var nextCursor by mutableStateOf<Int?>(null)
        private set
    var loadingMore by mutableStateOf(false)
        private set

    var myPosts by mutableStateOf<List<Community.Item>>(emptyList())
        private set
    var myPostsStatus by mutableStateOf(FeedStatus.Idle)
        private set

    var myLikes by mutableStateOf<List<Community.Item>>(emptyList())
        private set
    var likesStatus by mutableStateOf(FeedStatus.Idle)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val token: String? get() = auth.token

    suspend fun refreshFeed() {
        if (feedStatus == FeedStatus.Loading) return
        feedStatus = FeedStatus.Loading
        error = null
        when (val result = Community.feed(token)) {
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
        when (val result = Community.feed(token, cursor)) {
            is Community.Result.Ok -> {
                val known = feed.map { it.id }.toHashSet()
                feed = feed + result.value.items.filter { it.id !in known }
                nextCursor = result.value.nextCursor
            }
            else -> Unit
        }
        loadingMore = false
    }

    suspend fun refreshMine() {
        val current = token ?: run {
            myPosts = emptyList()
            myPostsStatus = FeedStatus.Idle
            return
        }
        myPostsStatus = FeedStatus.Loading
        when (val result = Community.mine(current)) {
            is Community.Result.Ok -> {
                myPosts = result.value.items
                myPostsStatus = if (result.value.items.isEmpty()) FeedStatus.Empty else FeedStatus.Ready
            }
            is Community.Result.Denied -> myPostsStatus = if (myPosts.isEmpty()) FeedStatus.Offline else FeedStatus.Ready
            is Community.Result.Failed -> myPostsStatus = if (myPosts.isEmpty()) FeedStatus.Offline else FeedStatus.Ready
        }
    }

    suspend fun refreshLikes() {
        val current = token ?: run {
            myLikes = emptyList()
            likesStatus = FeedStatus.Idle
            return
        }
        likesStatus = FeedStatus.Loading
        when (val result = Community.likes(current)) {
            is Community.Result.Ok -> {
                myLikes = result.value.items
                likesStatus = if (result.value.items.isEmpty()) FeedStatus.Empty else FeedStatus.Ready
            }
            is Community.Result.Denied -> likesStatus = FeedStatus.Ready
            is Community.Result.Failed -> likesStatus = FeedStatus.Offline
        }
    }

    suspend fun toggleLike(item: Community.Item): Boolean = withContext(Dispatchers.IO) {
        val current = token ?: return@withContext false
        val target = !item.liked
        when (val result = Community.like(item.id, current, target)) {
            is Community.Result.Ok -> {
                fun patch(list: List<Community.Item>) = list.map {
                    if (it.id == item.id) {
                        it.copy(liked = target, likes = (it.likes + if (target) 1 else -1).coerceAtLeast(0))
                    } else it
                }
                feed = patch(feed)
                myPosts = patch(myPosts)
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
        bandHz: String,
        preset: String = "",
        strength: Int = 100
    ): Community.Result<Int> {
        error = null
        val current = token ?: return Community.Result.Denied(401, "unauthorized")
        val result = Community.publish(
            token = current,
            name = name,
            curve = curve,
            model = model,
            deviceLabel = deviceLabel,
            bandHz = bandHz,
            preset = preset,
            strength = strength
        )
        when (result) {
            is Community.Result.Ok -> {
                refreshFeed()
                refreshMine()
            }
            is Community.Result.Denied -> error = result.message ?: "服务器拒绝了这次发布（" + result.code + "）。"
            is Community.Result.Failed -> error = "连不上社区服务器，等网络正常再试。"
        }
        return result
    }

    /** Deletes one of my published curves. The server enforces ownership; this is the happy path. */
    suspend fun deletePost(item: Community.Item): Boolean {
        val current = token ?: return false
        return when (val result = Community.deletePreset(current, item.id)) {
            is Community.Result.Ok -> {
                feed = feed.filterNot { it.id == item.id }
                myPosts = myPosts.filterNot { it.id == item.id }
                myLikes = myLikes.filterNot { it.id == item.id }
                if (myPosts.isEmpty()) myPostsStatus = FeedStatus.Empty
                error = null
                true
            }
            is Community.Result.Denied -> {
                error = result.message ?: "删除失败（" + result.code + "）。"
                false
            }
            is Community.Result.Failed -> {
                error = "连不上社区服务器，删除没完成。"
                false
            }
        }
    }

    suspend fun addComment(presetId: Int, body: String): Community.Result<Unit> {
        val current = token ?: return Community.Result.Denied(401, "unauthorized")
        return when (val result = Community.comment(presetId, current, body)) {
            is Community.Result.Ok -> {
                feed = feed.map { if (it.id == presetId) it.copy(comments = it.comments + 1) else it }
                result
            }
            is Community.Result.Denied -> {
                error = result.message ?: "评论没发出去（" + result.code + "）。"
                result
            }
            is Community.Result.Failed -> {
                error = "连不上社区服务器，评论没发出去。"
                result
            }
        }
    }

    /** After signing in or out: whatever the feed said about ownership is no longer true. */
    fun resetForAuthChange() {
        feed = emptyList()
        feedStatus = FeedStatus.Idle
        nextCursor = null
        myPosts = emptyList()
        myPostsStatus = FeedStatus.Idle
        myLikes = emptyList()
        likesStatus = FeedStatus.Idle
        error = null
    }

    fun clearError() {
        error = null
    }
}