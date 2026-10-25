package com.twitchalarm.work

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.twitchalarm.data.AppDatabase
import com.twitchalarm.ui.SettingsActivity
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.gms.tasks.Tasks
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Best-effort relay registration; failures are logged and never escape to callers. */
object RelayApi {
    private const val TAG = "RelayApi"
    private const val WATCHLIST_URL = "https://bronyatenei.site/v1/watchlists"
    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private suspend fun firebaseBearerToken(): String? {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: Tasks.await(auth.signInAnonymously()).user
            ?: error("Firebase anonymous sign-in returned no user")
        return Tasks.await(user.getIdToken(false))?.token
    }
    suspend fun clearWatchlist(context: Context): Boolean {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
                val displayName = prefs.getString(SettingsActivity.KEY_SERVER_DISPLAY_NAME, "")?.trim().orEmpty()
                val token = firebaseBearerToken()
                val fcmToken = FcmTokenStore.get(appContext)
                if (displayName.isEmpty() || displayName.length > 40 || token.isNullOrBlank() || fcmToken.isNullOrBlank()) return@runCatching false
                val payload = JSONObject().apply {
                    put("displayName", displayName)
                    put("fcmToken", fcmToken)
                    put("channels", JSONArray())
                }
                val request = Request.Builder().url(WATCHLIST_URL)
                    .header("Authorization", "Bearer $token")
                    .post(payload.toString().toRequestBody(jsonType)).build()
                client.newCall(request).execute().use { it.isSuccessful }
            }.onFailure { Log.e(TAG, "Watchlist clear failed", it) }.getOrDefault(false)
        }
    }
    suspend fun deleteServerProfile(context: Context): Boolean {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                val token = firebaseBearerToken()
                if (token.isNullOrBlank()) return@runCatching false
                val request = Request.Builder().url("$WATCHLIST_URL/self")
                    .header("Authorization", "Bearer $token").delete().build()
                client.newCall(request).execute().use { it.isSuccessful }
            }.onFailure { Log.e(TAG, "Server profile deletion failed", it) }.getOrDefault(false)
        }
    }
    suspend fun syncWatchlist(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val channels = AppDatabase.getInstance(appContext).streamerDao().getEnabled()
                .map { it.login }
            val displayName = PreferenceManager.getDefaultSharedPreferences(appContext)
                .getString(SettingsActivity.KEY_SERVER_DISPLAY_NAME, "")?.trim().orEmpty()
            if (displayName.isEmpty() || displayName.length > 40) {
                Log.e(TAG, "Watchlist sync skipped: invalid server display name")
                return@runCatching
            }
            val token = firebaseBearerToken()
            if (token.isNullOrBlank()) {
                Log.e(TAG, "Watchlist sync skipped: Firebase ID token unavailable")
                return@runCatching
            }
            val payload = JSONObject().apply {
                put("ownerId", displayName)
                put("displayName", displayName)
                put("fcmToken", FcmTokenStore.get(appContext) ?: JSONObject.NULL)
                put("channels", JSONArray(channels))
            }
            val request = Request.Builder()
                .url(WATCHLIST_URL)
                .header("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody(jsonType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Watchlist sync failed: HTTP ${response.code}")
                } else {
                    Log.d(TAG, "Watchlist synced: ${channels.size} channels")
                }
            }
        }.onFailure { error -> Log.e(TAG, "Watchlist sync error", error) }
    }

    suspend fun health(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("https://bronyatenei.site/healthz").get().build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.onFailure { Log.e(TAG, "Relay health check failed", it) }.getOrDefault(false)
    }
}
