package br.com.felipezorzo.zpa.cli.tracker

import java.util.IdentityHashMap

class Tracking<R : Trackable, B : Trackable>(
    private val raws: Collection<R>,
    private val bases: Collection<B>
) {
    /**
     * Matched issues -> a raw issue is associated to a base issue
     */
    private val rawToBase = IdentityHashMap<R, B>()
    private val baseToRaw = IdentityHashMap<B, R>()

    /**
     * Returns an Iterable to be traversed when matching issues. That means
     * that the traversal does not fail if method [.match]
     * is called.
     */
    val unmatchedRaws: Iterable<R>
        get() {
            val result = mutableListOf<R>()
            for (r in raws) {
                if (!rawToBase.containsKey(r)) {
                    result.add(r)
                }
            }
            return result
        }
    val matchedRaws: Map<R, B>
        get() = rawToBase

    /**
     * The base issues that are not matched by a raw issue and that need to be closed.
     */
    val unmatchedBases: Iterable<B>
        get() {
            val result: MutableList<B> = ArrayList()
            for (b in bases) {
                if (!baseToRaw.containsKey(b)) {
                    result.add(b)
                }
            }
            return result
        }

    fun match(raw: R, base: B) {
        rawToBase[raw] = base
        baseToRaw[base] = raw
    }

    val isComplete: Boolean
        get() = rawToBase.size == raws.size

}