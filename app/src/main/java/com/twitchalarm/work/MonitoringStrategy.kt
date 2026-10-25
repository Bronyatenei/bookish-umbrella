package com.twitchalarm.work

/**
 * Стратегия работы Twitch-мониторинга.
 *
 * RELIABLE удерживает foreground-службу активной, пока включён хотя бы один канал.
 * ECONOMY завершает каждую проверку и планирует только одно следующее пробуждение.
 * HOME_AGENT ждёт FCM-события от домашнего агента.
 * SERVER регистрирует watchlist в relay и ждёт relay-события через FCM.
 */
enum class MonitoringStrategy(val storedValue: String) {
    RELIABLE("reliable"),
    ECONOMY("economy"),
    HOME_AGENT("home_agent"),
    SERVER("server");

    companion object {
        fun fromStoredValue(value: String?): MonitoringStrategy = values().firstOrNull {
            it.storedValue == value
        } ?: RELIABLE
    }
}
