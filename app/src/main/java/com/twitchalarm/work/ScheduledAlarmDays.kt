package com.twitchalarm.work

import java.util.Calendar

object ScheduledAlarmDays {
    data class Day(val calendarDay: Int, val shortName: String, val fullName: String)

    val ordered = listOf(
        Day(Calendar.MONDAY, "Пн", "Понедельник"),
        Day(Calendar.TUESDAY, "Вт", "Вторник"),
        Day(Calendar.WEDNESDAY, "Ср", "Среда"),
        Day(Calendar.THURSDAY, "Чт", "Четверг"),
        Day(Calendar.FRIDAY, "Пт", "Пятница"),
        Day(Calendar.SATURDAY, "Сб", "Суббота"),
        Day(Calendar.SUNDAY, "Вс", "Воскресенье")
    )

    fun bit(day: Int): Int = 1 shl day

    fun format(mask: Int): String {
        if (mask == 0) return "Один раз"
        val selected = ordered.filter { mask and bit(it.calendarDay) != 0 }
        if (selected.size == ordered.size) return "Каждый день"
        if (selected.map { it.calendarDay }.toSet() == setOf(
                Calendar.MONDAY,
                Calendar.TUESDAY,
                Calendar.WEDNESDAY,
                Calendar.THURSDAY,
                Calendar.FRIDAY
            )
        ) return "Будни"
        if (selected.map { it.calendarDay }.toSet() == setOf(Calendar.SATURDAY, Calendar.SUNDAY)) return "Выходные"
        return selected.joinToString(", ") { it.shortName }
    }
}
