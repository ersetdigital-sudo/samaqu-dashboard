package com.samaqu.keyboard.util

/**
 * Lets only the newest of several overlapping requests show its answer. Pure Kotlin.
 *
 * Tapping a button twice starts two requests; without this both answers are drawn and the list
 * of results appears doubled. Each request takes a ticket from [start] and, once its answer
 * arrives, draws it only if [isCurrent] still says its ticket is the newest.
 */
class LatestOnly {

    private var latest = 0

    /** Begins a request; every earlier ticket becomes stale. */
    fun start(): Int {
        latest += 1
        return latest
    }

    fun isCurrent(ticket: Int): Boolean = ticket == latest
}
