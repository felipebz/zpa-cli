package br.com.felipezorzo.zpa.cli

import com.felipebz.zpa.squid.ZpaIssue

internal object IssueOrdering {
    val COMPARATOR: Comparator<ZpaIssue> = Comparator { a, b ->
        val fileA = (a.file as? InputFile)?.pathRelativeToBase ?: a.file.fileName()
        val fileB = (b.file as? InputFile)?.pathRelativeToBase ?: b.file.fileName()
        val fileComp = fileA.compareTo(fileB)
        if (fileComp != 0) return@Comparator fileComp

        val locA = a.primaryLocation
        val locB = b.primaryLocation

        val startLineComp = locA.startLine().compareTo(locB.startLine())
        if (startLineComp != 0) return@Comparator startLineComp

        val startColComp = locA.startLineOffset().compareTo(locB.startLineOffset())
        if (startColComp != 0) return@Comparator startColComp

        val endLineComp = locA.endLine().compareTo(locB.endLine())
        if (endLineComp != 0) return@Comparator endLineComp

        val endColComp = locA.endLineOffset().compareTo(locB.endLineOffset())
        if (endColComp != 0) return@Comparator endColComp

        val ruleA = a.check.activeRule.ruleKey.toString()
        val ruleB = b.check.activeRule.ruleKey.toString()
        val ruleComp = ruleA.compareTo(ruleB)
        if (ruleComp != 0) return@Comparator ruleComp

        val msgA = locA.message()
        val msgB = locB.message()
        msgA.compareTo(msgB)
    }

    fun sort(issues: List<ZpaIssue>): List<ZpaIssue> = issues.sortedWith(COMPARATOR)
}
