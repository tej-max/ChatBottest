package com.persona.chat
import java.text.Normalizer
import kotlin.random.Random

/** 100% local, deterministic matching. Only reply selection is random. */
object Engine {
    private val lastReply = HashMap<Long, String>()

    fun norm(s: String, caseSensitive: Boolean, ignorePunct: Boolean): String {
        var t = Normalizer.normalize(s, Normalizer.Form.NFKC)
        if (!caseSensitive) t = t.lowercase()
        if (ignorePunct) t = t.replace(Regex("[\\p{P}\\p{S}]"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun matches(r: TriggerRule, message: String): Boolean {
        val m = norm(message, r.caseSensitive, r.ignorePunct)
        return r.keywords.split("\n").map { norm(it, r.caseSensitive, r.ignorePunct) }
            .filter { it.isNotEmpty() }.any { k ->
                when (r.mode) {
                    "EXACT" -> m == k
                    "CONTAINS" -> m.contains(k)
                    else -> Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(k) + "(?![\\p{L}\\p{N}])").containsMatchIn(m)
                }
            }
    }

    /** Highest-priority enabled match wins; returns rule + randomly chosen alternative. */
    fun choose(rules: List<TriggerRule>, message: String): Pair<TriggerRule, String>? {
        val r = rules.filter { it.enabled && matches(it, message) }.maxByOrNull { it.priority } ?: return null
        val opts = r.responses.split("\n").filter { it.isNotBlank() }
        if (opts.isEmpty() && r.media.isBlank()) return null
        var pool = opts
        if (r.noRepeat && opts.size > 1) pool = opts.filter { it != lastReply[r.id] }
        val pick = if (pool.isEmpty()) "" else pool[Random.nextInt(pool.size)]
        lastReply[r.id] = pick
        return r to pick
    }

    fun delayFor(r: TriggerRule): Long {
        val lo = minOf(r.minDelayMs, r.maxDelayMs); val hi = maxOf(r.minDelayMs, r.maxDelayMs)
        return if (hi <= lo) lo else Random.nextLong(lo, hi + 1)
    }
}
