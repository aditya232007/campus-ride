package com.example.data.model

import java.util.Calendar

enum class ScheduleDutyState {
    ON_DUTY,
    LUNCH_BREAK,
    OFF_DUTY
}

data class ScheduleStatus(
    val isAvailable: Boolean,
    val message: String,
    val dutyState: ScheduleDutyState = ScheduleDutyState.OFF_DUTY,
    val isLunchBreak: Boolean = false,
    val isAfterHours: Boolean = false
) {
    val statusBadgeLabel: String
        get() = when (dutyState) {
            ScheduleDutyState.ON_DUTY -> "🟢 On Duty"
            ScheduleDutyState.LUNCH_BREAK -> "🟡 Lunch Break"
            ScheduleDutyState.OFF_DUTY -> "🔴 Off Duty"
        }

    companion object {
        fun getCurrentStatus(overrideHours: Boolean = false): ScheduleStatus {
            if (overrideHours) {
                return ScheduleStatus(
                    isAvailable = true,
                    message = "🟢 On Duty (Testing Override Mode Active)",
                    dutyState = ScheduleDutyState.ON_DUTY
                )
            }

            val calendar = Calendar.getInstance()
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            val isWeekend = (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)

            if (isWeekend) {
                return ScheduleStatus(
                    isAvailable = false,
                    message = "🔴 Off Duty (Service closed on weekends: Mon-Fri 8 AM - 6 PM)",
                    dutyState = ScheduleDutyState.OFF_DUTY,
                    isAfterHours = true
                )
            }

            val hour = calendar.get(Calendar.HOUR_OF_DAY) // 0..23

            return when {
                hour in 8..12 -> ScheduleStatus(
                    isAvailable = true,
                    message = "🟢 On Duty (Morning Shift: 8:00 AM - 1:00 PM)",
                    dutyState = ScheduleDutyState.ON_DUTY
                )
                hour == 13 -> ScheduleStatus(
                    isAvailable = false,
                    message = "🟡 Lunch Break (1:00 PM - 2:00 PM). Driver unavailable.",
                    dutyState = ScheduleDutyState.LUNCH_BREAK,
                    isLunchBreak = true
                )
                hour in 14..17 -> ScheduleStatus(
                    isAvailable = true,
                    message = "🟢 On Duty (Afternoon Shift: 2:00 PM - 6:00 PM)",
                    dutyState = ScheduleDutyState.ON_DUTY
                )
                else -> ScheduleStatus(
                    isAvailable = false,
                    message = "🔴 Off Duty (Service closed after 6:00 PM. Hours: 8 AM - 6 PM)",
                    dutyState = ScheduleDutyState.OFF_DUTY,
                    isAfterHours = true
                )
            }
        }
    }
}

