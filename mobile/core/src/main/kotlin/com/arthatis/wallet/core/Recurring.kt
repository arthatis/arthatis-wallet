package com.arthatis.wallet.core

import java.time.LocalDate

fun nextOccurrence(record: Recurring, after: LocalDate): LocalDate? {
    if (record.deletedAt != null || record.paused) return null
    return when (record.frequency) {
        Frequency.daily -> after.plusDays(1)
        Frequency.weekly -> {
            val target = record.weekday ?: after.plusDays(1).dayOfWeek.value
            var cursor = after.plusDays(1)
            while (cursor.dayOfWeek.value != target) cursor = cursor.plusDays(1)
            cursor
        }
        Frequency.monthly -> {
            val day = (record.dayOfMonth ?: after.dayOfMonth).coerceIn(1, 28)
            val thisMonth = after.withDayOfMonth(day)
            if (thisMonth.isAfter(after)) thisMonth else thisMonth.plusMonths(1)
        }
        Frequency.yearly -> {
            val month = record.month ?: after.monthValue
            val day = (record.dayOfMonth ?: after.dayOfMonth).coerceIn(1, 28)
            val thisYear = LocalDate.of(after.year, month, day)
            if (thisYear.isAfter(after)) thisYear else thisYear.plusYears(1)
        }
    }
}

/** True when the next occurrence is tomorrow: the local reminder fires the day before (H-1). */
fun reminderDue(record: Recurring, today: LocalDate): Boolean {
    if (!record.reminder) return false
    return nextOccurrence(record, today) == today.plusDays(1)
}

/** Whether [record] should post a transaction on [today] (once per day). */
fun dueToday(record: Recurring, today: LocalDate, lastRun: LocalDate?): Boolean {
    if (record.deletedAt != null || record.paused) return false
    if (lastRun != null && !lastRun.isBefore(today)) return false
    val reference = lastRun ?: today.minusDays(1)
    val next = nextOccurrence(record, reference) ?: return false
    return !next.isAfter(today)
}
