package com.chrisb588.easlie.images

/** Cumulative requested-tier lookups during one process lifetime. */
internal class ImageProfileCounters {
    private var hits = 0L
    private var misses = 0L
    private var scheduled = 0L

    fun recordHit() { hits++ }
    fun recordMiss() { misses++ }
    fun recordScheduled() { scheduled++ }
    fun snapshot(): String = "refresh_hits=$hits refresh_misses=$misses decodes_scheduled=$scheduled"
}
