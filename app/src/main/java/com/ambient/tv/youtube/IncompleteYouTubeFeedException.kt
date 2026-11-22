package com.ambient.tv.youtube

/**
 * The bounded feed observation did not finish. This alone is not an IO failure: a stream can
 * hit its time/emission limit after delivering useful rows. A real transport cause is retained.
 */
internal class IncompleteYouTubeFeedException(cause: Throwable? = null) :
    Exception("YouTube feed refresh incomplete", cause)
