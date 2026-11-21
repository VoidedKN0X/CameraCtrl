package com.vknxworks.cameractrl.camera

/** What close-up switching needs to know about a lens: its closest focus as the phone reports it. */
data class FocusLens(
    val key: String,
    /** LENS_INFO_MINIMUM_FOCUS_DISTANCE, in diopters (1 / metres); 0 means fixed focus. */
    val minFocus: Float,
    /** The phone reports real diopters for this lens (approximate or calibrated), so lenses can be compared. */
    val calibrated: Boolean,
)

/**
 * Automatic lens switching for close-ups, from what the phone reports and nothing else: each lens's minimum
 * focus distance, and the focus distance and autofocus state of the live preview.
 */
object CloseUp {
    /**
     * The lens to switch to when [current] can't focus close enough: of [others], the one that focuses closest,
     * if it focuses clearly closer (at least 25 % more diopters). Lenses whose focus distances aren't real
     * diopters are never compared, and fixed-focus lenses are never picked.
     */
    fun closerLens(current: FocusLens, others: List<FocusLens>): FocusLens? {
        if (!current.calibrated || current.minFocus <= 0f) return null
        return others
            .filter { it.key != current.key && it.calibrated && it.minFocus > current.minFocus * 1.25f }
            .maxByOrNull { it.minFocus }
    }
}

/**
 * Decides when to switch, from the preview's focus over time. A switch needs the condition to hold for a while,
 * so a lens doesn't flip back and forth while autofocus is still moving.
 */
class CloseUpTracker(private val holdMs: Long = 1_000L, private val backMs: Long = 1_500L) {
    private var since = -1L

    fun reset() {
        since = -1L
    }

    /**
     * Too close for the lens in use: its focus is at (or next to) its closest distance [minFocus] and the
     * preview isn't sharp, for [holdMs]. [diopters] below 0 means the camera didn't report a distance.
     */
    fun tooClose(now: Long, diopters: Float, sharp: Boolean, minFocus: Float): Boolean =
        held(now, diopters >= 0f && minFocus > 0f && diopters >= minFocus * 0.97f && !sharp, holdMs)

    /**
     * Far enough again for the lens it came from: sharp, at a distance clearly inside what that lens can focus
     * ([minFocus] of the original lens), for [backMs].
     */
    fun farEnough(now: Long, diopters: Float, sharp: Boolean, minFocus: Float): Boolean =
        held(now, sharp && diopters >= 0f && diopters < minFocus * 0.8f, backMs)

    private fun held(now: Long, condition: Boolean, ms: Long): Boolean {
        if (!condition) {
            since = -1L
            return false
        }
        if (since < 0L) since = now
        return now - since >= ms
    }
}
