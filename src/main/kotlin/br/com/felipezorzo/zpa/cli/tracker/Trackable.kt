package br.com.felipezorzo.zpa.cli.tracker

interface Trackable {
    val ruleKey: String
    val message: String
    val line: Int
    val lineHash: String
    val textRangeHash: Int
    val serverIssueKey: String
    val path: String
}