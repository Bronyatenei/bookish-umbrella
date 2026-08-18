package com.twitchalarm.work

/**
 * Стратегия работы Twitch-мониторинга.
 *
 * RELIABLE удерживает foreground-службу активной, пока включён хотя бы один канал.
 * ECONOMY завершает каждую проверку и планирует только одно следующее пробуждение.
 * HOME_AGENT не выполняет сетевые проверки на телефоне и ждёт FCM-события от домашнего агента.
 */
enum class MonitoringStrategy(val storedValue: String) {
    RELIABLE("reliable"),
    ECONOMY("economy"),
    HOME_AGENT("home_agent");

    companion object {
        fun fromStoredValue(value: String?): MonitoringStrategy = values().firstOrNull {
            it.storedValue == value
        } ?: RELIABLE
    }
}
