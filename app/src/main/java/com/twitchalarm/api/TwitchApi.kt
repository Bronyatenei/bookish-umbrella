package com.twitchalarm.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Клиент для неофициального GraphQL API Twitch.
 * Использует публичный client-id веб-приложения Twitch.
 * API не требует OAuth — работает без авторизации.
 */
object TwitchApi {

    // Публичный Client-ID веб-клиента Twitch (используется браузерной версией twitch.tv)
    private const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    private const val GQL_URL   = "https://gql.twitch.tv/gql"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    data class StreamInfo(
        val login: String,
        val displayName: String,
        val isLive: Boolean,
        val streamId: String? = null,
        val title: String = "",
        val viewerCount: Int = 0,
        val gameName: String = ""
    )

    /**
     * Проверяет статус одного стримера.
     * Возвращает null при сетевой ошибке.
     */
    fun checkStream(login: String): StreamInfo? = runCatching {
        val query = """
            query StreamStatus(${'$'}login: String!) {
              user(login: ${'$'}login) {
                login
                displayName
                stream {
                  id
                  title
                  viewersCount
                  game {
                    name
                  }
                }
              }
            }
        """.trimIndent()

        val body = JSONObject().apply {
            put("query", query)
            put("variables", JSONObject().put("login", login))
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url(GQL_URL)
            .addHeader("Client-ID", CLIENT_ID)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: return null

        val json = JSONObject(responseBody)
        val user = json
            .optJSONObject("data")
            ?.optJSONObject("user") ?: return StreamInfo(login, login, false)

        val displayName = user.optString("displayName", login)
        val stream = user.optJSONObject("stream")

        if (stream == null) {
            StreamInfo(login, displayName, false)
        } else {
            StreamInfo(
                login       = login,
                displayName = displayName,
                isLive      = true,
                streamId    = stream.optString("id", "").takeIf { it.isNotBlank() },
                title       = stream.optString("title", ""),
                viewerCount = stream.optInt("viewersCount", 0),
                gameName    = stream.optJSONObject("game")?.optString("name", "") ?: ""
            )
        }
    }.getOrNull()

    /**
     * Проверяет несколько стримеров за один запрос (batch).
     * Более эффективно, чем вызывать checkStream() в цикле.
     */
    fun checkStreams(logins: List<String>): List<StreamInfo>? = runCatching {
        if (logins.isEmpty()) return emptyList()

        // Формируем batch-запрос через aliases
        val aliases = logins.mapIndexed { i, login ->
            """
            u$i: user(login: "$login") {
                login
                displayName
                stream {
                    id
                    title
                    viewersCount
                    game { name }
                }
            }
            """.trimIndent()
        }.joinToString("\n")

        val query = "{ $aliases }"

        val body = JSONObject().apply {
            put("query", query)
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url(GQL_URL)
            .addHeader("Client-ID", CLIENT_ID)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null
        val responseBody = response.body?.string() ?: return null

        val data = JSONObject(responseBody).optJSONObject("data") ?: return null

        logins.mapIndexed { i, login ->
            val user = data.optJSONObject("u$i")
            if (user == null) {
                StreamInfo(login, login, false)
            } else {
                val displayName = user.optString("displayName", login)
                val stream = user.optJSONObject("stream")
                if (stream == null) {
                    StreamInfo(login, displayName, false)
                } else {
                    StreamInfo(
                        login       = login,
                        displayName = displayName,
                        isLive      = true,
                        title       = stream.optString("title", ""),
                        viewerCount = stream.optInt("viewersCount", 0),
                        gameName    = stream.optJSONObject("game")?.optString("name", "") ?: ""
                    )
                }
            }
        }
    }.getOrNull()
}
