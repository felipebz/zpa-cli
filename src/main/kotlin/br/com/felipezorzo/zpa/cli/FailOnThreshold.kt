package br.com.felipezorzo.zpa.cli

import com.felipebz.zpa.checks.ParsingErrorCheck
import com.felipebz.zpa.squid.ZpaIssue
import java.util.Locale

enum class FailOnThreshold {
    NONE,
    ANY,
    SYNTAX,
    BLOCKER,
    CRITICAL,
    MAJOR,
    MINOR,
    INFO;

    val cliName: String
        get() = name.lowercase(Locale.ROOT)

    companion object {
        fun fromString(value: String): FailOnThreshold {
            val normalized = value.trim().uppercase(Locale.ROOT)
            return entries.firstOrNull { it.name == normalized }
                ?: throw CliValidationException(
                    "Invalid --fail-on value: '$value'. Supported values: none, any, syntax, blocker, critical, major, minor, info"
                )
        }
    }

    fun hasFailure(issues: List<ZpaIssue>): Boolean {
        if (this == NONE || issues.isEmpty()) {
            return false
        }
        if (this == ANY) {
            return true
        }
        if (this == SYNTAX) {
            return issues.any { it.check is ParsingErrorCheck }
        }

        val severityRanks = mapOf(
            "INFO" to 1,
            "MINOR" to 2,
            "MAJOR" to 3,
            "CRITICAL" to 4,
            "BLOCKER" to 5
        )

        val minRank = when (this) {
            INFO -> 1
            MINOR -> 2
            MAJOR -> 3
            CRITICAL -> 4
            BLOCKER -> 5
            NONE, ANY, SYNTAX -> return false
        }

        return issues.any { issue ->
            val rank = severityRanks[issue.check.activeRule.severity.uppercase(Locale.ROOT)] ?: 0
            rank >= minRank
        }
    }
}
