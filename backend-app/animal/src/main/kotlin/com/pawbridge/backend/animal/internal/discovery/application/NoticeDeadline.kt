package com.pawbridge.backend.animal.internal.discovery.application

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Days left until the notice ends: 0 on the end date itself, negative once the notice has passed. */
internal fun daysUntilNoticeEnd(today: LocalDate, noticeEndDate: LocalDate): Long =
    ChronoUnit.DAYS.between(today, noticeEndDate)

/** True when the notice ends within three days, today included. A notice that already ended is not closing soon. */
internal fun isNoticeClosingSoon(today: LocalDate, noticeEndDate: LocalDate): Boolean {
    val remaining = daysUntilNoticeEnd(today, noticeEndDate)
    return remaining in 0..3
}
