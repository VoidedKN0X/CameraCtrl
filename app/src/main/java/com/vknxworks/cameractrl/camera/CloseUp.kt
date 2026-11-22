package com.vknxworks.cameractrl.camera

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** What close-up switching needs to know about a lens, as the phone reports it. */
data class FocusLens(
    val key: String,
    /** LENS_INFO_MINIMUM_FOCUS_DISTANCE: closest focus, in this lens's own focus units; 0 means fixed focus. */
    val minFocus: Float,
    /** The phone reports real diopters for this lens (approximate or calibrated), so lenses can be compared. */
    val calibrated: Boolean,
    /** 35 mm equivalent focal length (smaller is wider); 0 when the phone doesn't report enough to work it out. */
    val equivMm: Int = 0,
)

/** What to do when the lens in use can't focus close enough. */
sealed interface CloseUpPlan {
    /** Open this lens (a physical lens of the multi-camera, or a camera of its own). */
    data class Switch(val lens: FocusLens) : CloseUpPlan

    /** Stay on the AUTO multi-camera and set its zoom ratio to [ratio] (its widest), so the phone uses its widest lens. */
    data class Zoom(val ratio: Float) : CloseUpPlan
}

/**
 * Automatic lens switching for close-ups, from what the phone reports and nothing else: each lens's minimum
 * focus distance, calibration and focal length, and the focus distance and autofocus state of the live preview.
 */
object CloseUp {
    /** Focus at or beyond this share of a lens's closest focus counts as "at its near end". */
    const val NEAR = 0.9f

    /** Lens keys that failed to open during this run of the app (shown on the camera modes page). */
    val unopenable: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /**
     * The lens to switch to when [current] can't focus close enough, of [others] (the same side's lenses).
     * Fixed-focus lenses are never picked.
     *
     * 1. Where both lenses report real diopters: the one that focuses closest, if it focuses clearly closer
     *    (at least 25 % more diopters).
     * 2. Otherwise (a lens reports focus in its own units only): the widest lens with autofocus that is clearly
     *    wider than [current]. Phones put their close-up (macro) focus on the ultrawide.
     */
    fun closerLens(current: FocusLens, others: List<FocusLens>): FocusLens? {
        val af = others.filter { it.key != current.key && it.minFocus > 0f }
        if (current.calibrated && current.minFocus > 0f) {
            af.filter { it.calibrated && it.minFocus > current.minFocus * 1.25f }.maxByOrNull { it.minFocus }?.let { return it }
        }
        if (current.equivMm <= 0) return null
        return af
            .filter { !(current.calibrated && it.calibrated) && it.equivMm > 0 && it.equivMm < current.equivMm * 0.85f }
            .minByOrNull { it.equivMm }
    }

    /**
     * The full decision: switch to [closerLens] unless it is known not to open; on the AUTO multi-camera
     * ([logicalZoomMin] is its smallest zoom ratio; null on a single lens) zoom out to the widest lens instead
     * when no lens can be opened for it: none is listed, or the one that would be used failed to open.
     */
    fun plan(current: FocusLens, others: List<FocusLens>, unopenable: Set<String>, logicalZoomMin: Float?, zoomNow: Float): CloseUpPlan? {
        closerLens(current, others.filter { it.key !in unopenable })?.let { return CloseUpPlan.Switch(it) }
        if (logicalZoomMin == null || logicalZoomMin >= 0.95f || zoomNow <= logicalZoomMin + 0.05f) return null
        val listed = others.filter { it.key != current.key }
        return if (listed.isEmpty() || closerLens(current, listed) != null) CloseUpPlan.Zoom(logicalZoomMin) else null
    }

    /**
     * On the close-up lens: the focus distance (in the close-up lens's units) below which the subject is far
     * enough to go back. With real diopters on both lenses: clearly inside what the original lens [from] can focus.
     * Otherwise a share of the close-up lens's own range ([macro], or [from] when it isn't known). Each quick
     * switch back and forth ([bounces]) halves it, so the lenses don't keep flipping.
     */
    fun backBelow(from: FocusLens, macro: FocusLens?, bounces: Int): Float {
        val base = when {
            macro == null || macro.minFocus <= 0f -> from.minFocus * 0.4f
            from.calibrated && macro.calibrated -> from.minFocus * 0.8f
            else -> macro.minFocus * 0.4f
        }
        var f = base
        repeat(min(bounces, 3)) { f *= 0.5f }
        return f
    }
}

/**
 * Decides when to switch, from the preview's focus over time. Every switch needs its condition to hold for a
 * while, so a lens doesn't flip back and forth while autofocus is still moving. One tracker is used for one
 * question at a time ([tooClose], [farEnough] or [movedAway]).
 */
class CloseUpTracker(
    private val holdMs: Long = 1_000L,
    private val blindHoldMs: Long = 3_000L,
    private val backMs: Long = 1_500L,
    private val clearMs: Long = 1_500L,
) {
    private var since = -1L
    private var blind = false
    private var scanBase = 0

    fun reset() {
        since = -1L
    }

    /**
     * Too close for the lens in use, whose closest focus is [minFocus] (in the same units as [diopters], so
     * calibration doesn't matter). With a distance: focus at (or next to) the lens's closest distance and the
     * preview not sharp, for [holdMs]. Without one ([diopters] below 0): not sharp for [blindHoldMs], ending
     * [unfocused] or after at least two autofocus scans that failed ([failedScans] counts them).
     */
    fun tooClose(now: Long, diopters: Float, sharp: Boolean, unfocused: Boolean, failedScans: Int, minFocus: Float): Boolean {
        val measured = diopters >= 0f
        val cond = minFocus > 0f && !sharp && (!measured || diopters >= minFocus * CloseUp.NEAR)
        if (!cond) {
            reset()
            return false
        }
        if (since < 0L || blind == measured) {
            since = now
            blind = !measured
            scanBase = failedScans
        }
        val held = now - since
        return if (!blind) held >= holdMs else held >= blindHoldMs && (unfocused || failedScans - scanBase >= 2)
    }

    /** Far enough again: sharp, at a distance below [below] (see [CloseUp.backBelow]), for [backMs]. */
    fun farEnough(now: Long, diopters: Float, sharp: Boolean, below: Float): Boolean =
        held(now, sharp && diopters >= 0f && diopters < below, backMs)

    /**
     * After going back by hand: the subject has moved away from the lens's near end ([minFocus]) and is sharp
     * for [clearMs] (sharp alone when the phone reports no distance).
     */
    fun movedAway(now: Long, diopters: Float, sharp: Boolean, minFocus: Float): Boolean =
        held(now, sharp && (diopters < 0f || minFocus <= 0f || diopters < minFocus * 0.8f), clearMs)

    private fun held(now: Long, condition: Boolean, ms: Long): Boolean {
        if (!condition) {
            since = -1L
            return false
        }
        if (since < 0L) since = now
        return now - since >= ms
    }
}

fun Lens.focusLens() = FocusLens(key, minFocus, focusCalibrated, equivMm)

/**
 * The lens the camera is really using: on the AUTO multi-camera the physical lens the phone reports as active
 * ([activePhysicalId]) where it is one of [group], otherwise [lens] itself.
 */
fun activeLens(lens: Lens, group: List<Lens>, activePhysicalId: String?): Lens {
    if (!lens.logical || activePhysicalId == null) return lens
    return group.firstOrNull { it.id == lens.id && it.physicalId == activePhysicalId }
        ?: group.firstOrNull { it.physicalId == null && it.id == activePhysicalId && !it.logical }
        ?: lens
}
