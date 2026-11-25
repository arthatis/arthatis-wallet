package com.arthatis.wallet.core

import java.time.LocalDate

/** The due date for [dueDay] in the month of [month]; a day past the month's end becomes its last day. */
fun dueDateIn(dueDay: Int, month: LocalDate): LocalDate =
    month.withDayOfMonth(dueDay.coerceIn(1, month.lengthOfMonth()))

/** The next due date on or after [today]. */
fun nextDueDate(dueDay: Int, today: LocalDate): LocalDate {
    val thisMonth = dueDateIn(dueDay, today)
    return if (!thisMonth.isBefore(today)) thisMonth else dueDateIn(dueDay, today.plusMonths(1))
}

/** Days before the due date when a card bill reminder fires: 3 (H-3) or 0 (the day itself); null on other days. */
fun cardReminderDays(dueDay: Int, today: LocalDate): Int? {
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, nextDueDate(dueDay, today)).toInt()
    return days.takeIf { it == 3 || it == 0 }
}
