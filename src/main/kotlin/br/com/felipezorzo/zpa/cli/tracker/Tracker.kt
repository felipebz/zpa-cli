package br.com.felipezorzo.zpa.cli.tracker

import java.util.*

class Tracker<R : Trackable, B : Trackable> {
    fun track(
        rawTrackableSupplier: Collection<R>,
        baseTrackableSupplier: Collection<B>
    ): Tracking<R, B> {
        val tracking = Tracking(rawTrackableSupplier, baseTrackableSupplier)

        // 1. match issues with same server issue key
        match(tracking) { ServerIssueSearchKey(it) }

        // 2. match issues with same rule, same line and same text range hash, but not necessarily with same message
        match(tracking) { LineAndTextRangeHashKey(it) }

        // 3. match issues with same rule, same message and same text range hash
        match(tracking) { TextRangeHashAndMessageKey(it) }

        // 4. match issues with same rule, same line and same message
        match(tracking) { LineAndMessageKey(it) }

        // 5. match issues with same rule and same text range hash but different line and different message.
        // See SONAR-2812
        match(tracking) { TextRangeHashKey(it) }

        // 6. match issues with same rule, same line and same line hash
        match(tracking) { LineAndLineHashKey(it) }

        // 7. match issues with same rule and same same line hash
        match(tracking) { LineHashKey(it) }
        return tracking
    }

    private fun match(tracking: Tracking<R, B>, factory: SearchKeyFactory) {
        if (tracking.isComplete) {
            return
        }
        val baseSearch: MutableMap<SearchKey, MutableList<B>> = HashMap()
        for (base in tracking.unmatchedBases) {
            val searchKey = factory.invoke(base)
            if (!baseSearch.containsKey(searchKey)) {
                baseSearch[searchKey] = ArrayList()
            }
            baseSearch[searchKey]!!.add(base)
        }
        for (raw in tracking.unmatchedRaws) {
            val rawKey = factory.invoke(raw)
            val bases: Collection<B>? = baseSearch[rawKey]
            if (bases != null && !bases.isEmpty()) {
                val match = bases.iterator().next()
                tracking.match(raw, match)
                baseSearch[rawKey]!!.remove(match)
            }
        }
    }

    private interface SearchKey

    private fun interface SearchKeyFactory : (Trackable) -> SearchKey

    private class LineAndTextRangeHashKey constructor(trackable: Trackable) :
        SearchKey {
        private val ruleKey = trackable.ruleKey
        private val textRangeHash = trackable.textRangeHash
        private val line = trackable.line

        // note: the design of the enclosing caller ensures that 'o' is of the correct class and not null
        override fun equals(other: Any?): Boolean {
            val that = other as LineAndTextRangeHashKey?
            // start with most discriminant field
            return (that != null
                    && Objects.equals(line, that.line)
                    && Objects.equals(textRangeHash, that.textRangeHash)
                    && ruleKey == that.ruleKey)
        }

        override fun hashCode(): Int {
            var result = ruleKey.hashCode()
            result = 31 * result + textRangeHash.hashCode()
            result = 31 * result + line.hashCode()
            return result
        }
    }

    private class LineAndLineHashKey(trackable: Trackable) :
        SearchKey {
        private val ruleKey = trackable.ruleKey
        private val line = trackable.line
        private val lineHash = trackable.lineHash

        // note: the design of the enclosing caller ensures that 'o' is of the correct class and not null
        override fun equals(other: Any?): Boolean {
            val that = other as LineAndLineHashKey?
            // start with most discriminant field
            return that != null
                    && (Objects.equals(line, that.line)
                    && Objects.equals(lineHash, that.lineHash)
                    && ruleKey == that.ruleKey)
        }

        override fun hashCode(): Int {
            var result = ruleKey.hashCode()
            result = 31 * result + lineHash.hashCode()
            result = 31 * result + line.hashCode()
            return result
        }
    }

    private class LineHashKey(trackable: Trackable) :
        SearchKey {
        private val ruleKey = trackable.ruleKey
        private val lineHash = trackable.lineHash

        // note: the design of the enclosing caller ensures that 'o' is of the correct class and not null
        override fun equals(other: Any?): Boolean {
            val that = other as LineHashKey?
            // start with most discriminant field
            return that != null
                    && (Objects.equals(lineHash, that.lineHash)
                    && ruleKey == that.ruleKey)
        }

        override fun hashCode(): Int {
            var result = ruleKey.hashCode()
            result = 31 * result + lineHash.hashCode()
            return result
        }
    }

    private class TextRangeHashAndMessageKey(trackable: Trackable) :
        SearchKey {
        private val ruleKey = trackable.ruleKey
        private val message = trackable.message
        private val textRangeHash = trackable.textRangeHash

        // note: the design of the enclosing caller ensures that 'o' is of the correct class and not null
        override fun equals(other: Any?): Boolean {
            val that = other as TextRangeHashAndMessageKey?
            // start with most discriminant field
            return that != null
                    && (Objects.equals(textRangeHash, that.textRangeHash)
                    && message == that.message && ruleKey == that.ruleKey)
        }

        override fun hashCode(): Int {
            var result = ruleKey.hashCode()
            result = 31 * result + message.hashCode()
            result = 31 * result + textRangeHash.hashCode()
            return result
        }
    }

    private class LineAndMessageKey(trackable: Trackable) :
        SearchKey {
        private val ruleKey = trackable.ruleKey
        private val message = trackable.message
        private val line = trackable.line

        // note: the design of the enclosing caller ensures that 'o' is of the correct class and not null
        override fun equals(other: Any?): Boolean {
            val that = other as LineAndMessageKey?
            // start with most discriminant field
            return that != null
                    && (Objects.equals(line, that.line)
                    && message == that.message && ruleKey == that.ruleKey)
        }

        override fun hashCode(): Int {
            var result = ruleKey.hashCode()
            result = 31 * result + message.hashCode()
            result = 31 * result + line.hashCode()
            return result
        }
    }

    private class TextRangeHashKey constructor(trackable: Trackable) :
        SearchKey {
        private val ruleKey = trackable.ruleKey
        private val textRangeHash = trackable.textRangeHash

        // note: the design of the enclosing caller ensures that 'o' is of the correct class and not null
        override fun equals(other: Any?): Boolean {
            val that = other as TextRangeHashKey?
            // start with most discriminant field
            return that != null
                    && (Objects.equals(textRangeHash, that.textRangeHash)
                    && ruleKey == that.ruleKey)
        }

        override fun hashCode(): Int {
            var result = ruleKey.hashCode()
            result = 31 * result + textRangeHash.hashCode()
            return result
        }
    }

    private class ServerIssueSearchKey(trackable: Trackable) :
        SearchKey {
        private val serverIssueKey: String = trackable.serverIssueKey

        // note: the design of the enclosing caller ensures that 'o' is of the correct class and not null
        override fun equals(other: Any?): Boolean {
            val that = other as ServerIssueSearchKey?
            return that != null
                    && !isBlank(serverIssueKey)
                    && !isBlank(that.serverIssueKey)
                    && serverIssueKey == that.serverIssueKey
        }

        override fun hashCode(): Int {
            return serverIssueKey.hashCode()
        }

        private fun isBlank(s: String?): Boolean {
            return s == null || s.isEmpty()
        }
    }
}