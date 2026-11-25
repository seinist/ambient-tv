package com.ambient.tv.ui.home

/**
 * Only live TV programme claims and saved-source markers depend on read confirmation in
 * Home's row data. Other source reports update their own badges without rebuilding Home.
 */
internal class HomeTvReadAvailability {
    private var published = emptyMap<String, Boolean>()

    /** Capture once per distinct source, even when it appears in several channel rows. */
    fun capture(sources: Iterable<String>, unconfirmed: (String) -> Boolean): Map<String, Boolean> =
        sources.distinct().associateWith(unconfirmed).also { published = it }

    /** A repeated failure or an unrelated provider report does not change visible TV claims. */
    fun changed(unconfirmed: (String) -> Boolean): Boolean =
        published.any { (source, previous) -> unconfirmed(source) != previous }
}
