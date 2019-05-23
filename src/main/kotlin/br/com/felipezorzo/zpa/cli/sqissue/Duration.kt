package br.com.felipezorzo.zpa.cli.sqissue

import java.util.regex.Pattern

object Duration {

    private const val HOURS_IN_DAY = 8
    private const val MINUTES_IN_HOUR = 60
    private const val DAY = "d"
    private const val HOUR = "h"
    private const val MINUTE = "min"

    private val pattern: Pattern =
            Pattern.compile("\\s*+(?:(\\d++)\\s*+$DAY)?+\\s*+(?:(\\d++)\\s*+$HOUR)?+\\s*+(?:(\\d++)\\s*+$MINUTE)?+\\s*+")

    fun toMinute(duration: String): Int {
        val matcher = pattern.matcher(duration)

        var days = 0
        var hours = 0
        var minutes = 0

        if (matcher.find()) {
            val daysDuration = matcher.group(1)
            if (daysDuration != null) {
                days = Integer.parseInt(daysDuration)
            }
            val hoursText = matcher.group(2)
            if (hoursText != null) {
                hours = Integer.parseInt(hoursText)
            }
            val minutesText = matcher.group(3)
            if (minutesText != null) {
                minutes = Integer.parseInt(minutesText)
            }
        }

        return (days * HOURS_IN_DAY * MINUTES_IN_HOUR) + (hours * MINUTES_IN_HOUR) + minutes
    }
}