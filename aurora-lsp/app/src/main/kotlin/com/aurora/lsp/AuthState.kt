package com.aurora.lsp

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The signed-in account.
 *
 * <p>One mailbox is one account, and the mailbox is only ever used to sign in and to recover the
 * account: it never leaves the server's own rows and is never shown next to a published curve.
 * The token is the whole session - losing it just means signing in again.
 */
class AuthState(private val prefs: SharedPreferences) {

    var token by mutableStateOf(prefs.getString(KEY_TOKEN, null))
        private set
    var email by mutableStateOf(prefs.getString(KEY_EMAIL, "").orEmpty())
        private set
    var nickname by mutableStateOf(prefs.getString(KEY_NICKNAME, "").orEmpty())
        private set
    var avatar by mutableStateOf(prefs.getString(KEY_AVATAR, "").orEmpty())
        private set

    /** "admin" unlocks moderation; everything else behaves the same. */
    var role by mutableStateOf(prefs.getString(KEY_ROLE, "user").orEmpty().ifBlank { "user" })
        private set

    val isAdmin: Boolean get() = role == "admin"

    var error by mutableStateOf<String?>(null)
        private set

    val loggedIn: Boolean get() = !token.isNullOrBlank()

    /**
     * The anonymous handle this install used before accounts existed. It rides along on the first
     * sign-in so whatever the device published back then ends up on the account instead of being
     * stranded on a handle nobody can reach any more.
     */
    val legacyHandle: String
        get() = synchronized(prefs) {
            prefs.getString(KEY_ANON, null)?.let { return it }
            val value = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_ANON, value).apply()
            value
        }

    suspend fun requestCode(rawEmail: String): Boolean {
        val address = rawEmail.trim()
        if (!address.contains('@') || address.length < 6) {
            error = "邮箱格式不对，检查一下。"
            return false
        }
        return when (val result = Community.requestCode(address)) {
            is Community.Result.Ok -> {
                error = null
                true
            }
            is Community.Result.Denied -> {
                error = if (result.code == 404) {
                    // The deployed service predates accounts; saying "404" here would read as a bug.
                    "社区服务还是旧版本，暂时不能登录。"
                } else {
                    reasonOf(result)
                }
                false
            }
            is Community.Result.Failed -> {
                error = "连不上社区服务器，检查一下网络。"
                false
            }
        }
    }

    suspend fun verify(rawEmail: String, rawCode: String): Boolean {
        val code = rawCode.trim()
        if (code.length != 6 || !code.all { it.isDigit() }) {
            error = "验证码是 6 位数字。"
            return false
        }
        return when (val result = Community.verify(rawEmail.trim(), code, legacyHandle)) {
            is Community.Result.Ok -> {
                apply(result.value)
                error = null
                true
            }
            is Community.Result.Denied -> {
                error = reasonOf(result)
                false
            }
            is Community.Result.Failed -> {
                error = "连不上社区服务器，检查一下网络。"
                false
            }
        }
    }

    /** Re-validates the token on launch and pulls the current nickname/avatar. */
    suspend fun refresh() {
        val current = token ?: return
        when (val result = Community.me(current)) {
            is Community.Result.Ok -> {
                nickname = result.value.nickname.ifBlank { nickname }
                avatar = result.value.avatar.ifBlank { avatar }
                email = result.value.email.ifBlank { email }
                role = result.value.role
                persist()
            }
            is Community.Result.Denied -> if (result.code == 401) clear()
            is Community.Result.Failed -> Unit
        }
    }

    /** Updates the profile. [newAvatar] is null when the picture was not touched: an uploaded avatar
     is stored as "img:<file>", which the server rightly refuses to accept back as an icon key. */
    suspend fun updateProfile(newNickname: String, newAvatar: String?): Boolean {
        val current = token ?: return false
        return when (val result = Community.updateProfile(current, newNickname.ifBlank { null }, newAvatar)) {
            is Community.Result.Ok -> {
                nickname = result.value.nickname.ifBlank { newNickname }
                avatar = result.value.avatar.ifBlank { avatar }
                persist()
                error = null
                true
            }
            is Community.Result.Denied -> {
                error = reasonOf(result)
                false
            }
            is Community.Result.Failed -> {
                error = "连不上社区服务器，资料没保存。"
                false
            }
        }
    }

    suspend fun signOut() {
        token?.let { current -> withContext(Dispatchers.IO) { Community.logout(current) } }
        clear()
    }

    /**
     * Sends a picture the user already cropped. The server stores it and answers with the key the
     * account now uses, so the new avatar shows up here without a full profile round trip.
     */
    suspend fun uploadAvatar(jpeg: ByteArray): Boolean {
        val current = token ?: return false
        return when (val result = Community.uploadAvatar(current, jpeg)) {
            is Community.Result.Ok -> {
                avatar = result.value
                persist()
                error = null
                true
            }
            is Community.Result.Denied -> {
                error = result.message ?: "头像没传上去（" + result.code + "）。"
                false
            }
            is Community.Result.Failed -> {
                error = "连不上社区服务器，头像没传上去。"
                false
            }
        }
    }

    suspend fun deleteAccount(): Boolean {
        val current = token ?: return false
        return when (val result = Community.deleteAccount(current)) {
            is Community.Result.Ok -> {
                clear()
                error = null
                true
            }
            is Community.Result.Denied -> {
                error = reasonOf(result)
                false
            }
            is Community.Result.Failed -> {
                error = "连不上社区服务器，注销没完成。"
                false
            }
        }
    }

    fun clearError() {
        error = null
    }

    private fun apply(session: Community.Session) {
        token = session.token
        email = session.profile.email
        nickname = session.profile.nickname
        avatar = session.profile.avatar
        role = session.profile.role
        persist()
    }

    private fun clear() {
        token = null
        email = ""
        nickname = ""
        avatar = ""
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_EMAIL)
            .remove(KEY_NICKNAME)
            .remove(KEY_AVATAR)
            .remove(KEY_ROLE)
            .apply()
    }

    private fun persist() {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_EMAIL, email)
            .putString(KEY_NICKNAME, nickname)
            .putString(KEY_AVATAR, avatar)
            .putString(KEY_ROLE, role)
            .apply()
    }

    private fun reasonOf(denied: Community.Result.Denied): String = when (denied.error) {
        "tooSoon" -> "刚发过一次，等一分钟再试。"
        "dailyLimit" -> "这个邮箱今天发得太多了，明天再来。"
        "mailFailed" -> "验证码没发出去。服务器发信可能还没配好，稍后再试。"
        "badEmail" -> "邮箱格式不对，检查一下。"
        "expired" -> denied.message ?: "验证码过期了，重新发一次。"
        "tooMany" -> denied.message ?: "错误次数太多，重新发一次验证码。"
        "badCode" -> denied.message ?: "验证码不对。"
        "unauthorized" -> "登录已失效，重新登录一次。"
        else -> denied.message ?: "服务器拒绝了请求（" + denied.code + "）。"
    }

    companion object {
        private const val KEY_TOKEN = "community_token"
        private const val KEY_EMAIL = "community_email"
        private const val KEY_NICKNAME = "community_nickname"
        private const val KEY_AVATAR = "community_avatar"
        private const val KEY_ROLE = "community_role"
        private const val KEY_ANON = "anonId"
    }
}