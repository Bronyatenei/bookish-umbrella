package com.twitchalarm.work

import android.content.Context
import com.twitchalarm.api.TwitchApi
import com.twitchalarm.data.AppDatabase

/**
 * Общая атомарная проверка всех включённых каналов.
 * Её используют и непрерывная foreground-служба, и экономичный одноразовый запуск.
 */
object StreamStatusChecker {
    enum class Result {
        SUCCESS,
        NETWORK_FAILURE,
        NO_ENABLED_STREAMERS
    }

    suspend fun checkEnabledStreamers(context: Context): Result {
        val appContext = context.applicationContext
        val database = AppDatabase.getInstance(appContext)
        val streamers = database.streamerDao().getEnabled()
        if (streamers.isEmpty()) return Result.NO_ENABLED_STREAMERS

        val results = TwitchApi.checkStreams(streamers.map { it.login })
            ?: return Result.NETWORK_FAILURE

        results.forEach { info ->
            val previous = database.streamerDao().getByLogin(info.login) ?: return@forEach
            // Пользователь мог выключить канал, пока выполнялся HTTP-запрос.
            if (!previous.notifyEnabled) return@forEach

            database.streamerDao().updateLiveStatus(
                login = info.login,
                isLive = info.isLive,
                title = info.title,
                viewers = info.viewerCount,
                game = info.gameName,
                displayName = info.displayName
            )

            if (!previous.isLive && info.isLive &&
                StreamAlertDeduplicator.shouldTrigger(appContext, info.login, info.streamId)
            ) {
                AlarmPlaybackService.start(
                    context = appContext,
                    displayName = info.displayName,
                    title = info.title,
                    game = info.gameName,
                    viewers = info.viewerCount
                )
            }
        }

        return Result.SUCCESS
    }
}
