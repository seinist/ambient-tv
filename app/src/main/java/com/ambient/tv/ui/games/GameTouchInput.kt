package com.ambient.tv.ui.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.TouchControlStyle
import kotlin.math.abs
import kotlin.math.hypot

/** One continuous face-button area lets a thumb roll between buttons without being lifted. */
internal val GameTouchLayout.faceButtonsBounds: Rect?
    get() {
        val faces = faces.mapNotNull { buttons[it] }
        if (faces.isEmpty()) return null
        return Rect(
            faces.minOf { it.left }, faces.minOf { it.top },
            faces.maxOf { it.right }, faces.maxOf { it.bottom },
        )
    }

internal data class GameTouchInputState(
    val buttons: Set<RetroPadButton> = emptySet(),
    val leftStick: Offset = Offset.Zero,
    val rightStick: Offset = Offset.Zero,
    val leftStickHeld: Boolean = false,
    val rightStickHeld: Boolean = false,
)

/**
 * Shared contact state for the on-screen pad. Positions are in the layout's logical dp space.
 * Each visual receiver forwards its captured pointers here, including moves outside its bounds;
 * gaps have no receiver and can still deliver native pointer input to the game underneath.
 *
 * Like RetroArch's dpad_area / abxy_area, the two digital clusters have eight angular sectors.
 * A face-button gesture can roll into an adjacent button (or their two-button sector). D-pad
 * and analog gestures stay with their original control, even if the thumb slips off its edge.
 */
internal class GameTouchInput(private val layout: GameTouchLayout,
    style: TouchControlStyle = TouchControlStyle(),
) {
    private val deadzone = style.normalized().stickDeadzonePercent / 100f
    private val diagonalBoundary = when (style.diagonalSensitivity) {
        0 -> .65f
        2 -> .25f
        else -> .41421356f
    }
    private enum class Target { DPAD, LEFT_STICK, RIGHT_STICK, BUTTONS, IGNORED_STICK }
    private data class Contact(val target: Target, var position: Offset)

    private val contacts = linkedMapOf<Long, Contact>()
    private val faces = layout.faceButtonsBounds
    private val faceButtons = layout.faces.toSet()

    var state: GameTouchInputState = GameTouchInputState()
        private set

    /** True also means a neutral cluster-center contact was captured and must be consumed. */
    fun press(id: Long, position: Offset): Boolean {
        if (id in contacts) return move(id, position)
        var target = when {
            layout.dpad?.contains(position) == true -> Target.DPAD
            layout.leftStick?.contains(position) == true -> Target.LEFT_STICK
            layout.rightStick?.contains(position) == true -> Target.RIGHT_STICK
            faces?.contains(position) == true -> Target.BUTTONS
            layout.buttons.values.any { it.contains(position) } -> Target.BUTTONS
            else -> return false
        }
        // A second finger must neither move an occupied stick nor take it over on release.
        // It remains captured until UP, so it cannot turn into a stray OSD/native-screen tap.
        if ((target == Target.LEFT_STICK || target == Target.RIGHT_STICK) &&
            contacts.values.any { it.target == target }) {
            target = Target.IGNORED_STICK
        }
        contacts[id] = Contact(target, position)
        updateState()
        return true
    }

    fun move(id: Long, position: Offset): Boolean {
        val contact = contacts[id] ?: return false
        if (contact.position != position) {
            contact.position = position
            updateState()
        }
        return true
    }

    fun release(id: Long): Boolean {
        if (contacts.remove(id) == null) return false
        updateState()
        return true
    }

    /** Cancel, orientation changes and removal of the overlay always leave a neutral pad. */
    fun clear() {
        contacts.clear()
        state = GameTouchInputState()
    }

    private fun updateState() {
        var leftStick = Offset.Zero
        var rightStick = Offset.Zero
        var leftStickHeld = false
        var rightStickHeld = false
        val buttons = buildSet {
            contacts.values.forEach { contact ->
                when (contact.target) {
                    Target.DPAD -> layout.dpad?.let { bounds ->
                        addAll(dpadDirections(contact.position, bounds, diagonalBoundary))
                    }
                    Target.LEFT_STICK -> layout.leftStick?.let { bounds ->
                        leftStick = stickAxes(contact.position, bounds, deadzone)
                        leftStickHeld = true
                    }
                    Target.RIGHT_STICK -> layout.rightStick?.let { bounds ->
                        rightStick = stickAxes(contact.position, bounds, deadzone)
                        rightStickHeld = true
                    }
                    Target.BUTTONS -> addAll(buttonsAt(contact.position))
                    Target.IGNORED_STICK -> Unit
                }
            }
        }
        state = GameTouchInputState(buttons, leftStick, rightStick, leftStickHeld, rightStickHeld)
    }

    private fun buttonsAt(position: Offset): Set<RetroPadButton> {
        if (faces != null && faces.contains(position)) {
            if (layout.faceShape != GameTouchFaceShape.DIAMOND) return rolledFaceButtons(position)
            return eightWayButtons(
                position, faces, deadzone = .16f,
                up = RetroPadButton.X, down = RetroPadButton.B,
                left = RetroPadButton.Y, right = RetroPadButton.A,
            ).intersect(faceButtons)
        }
        return layout.buttons.entries.firstOrNull { (_, bounds) -> bounds.contains(position) }
            ?.let { setOf(it.key) }.orEmpty()
    }

    /** Two-/three-/six-button pads keep rolling chords without exposing phantom ABXY inputs. */
    private fun rolledFaceButtons(position: Offset): Set<RetroPadButton> {
        var nearest: RetroPadButton? = null
        var next: RetroPadButton? = null
        var distance = Float.POSITIVE_INFINITY
        var nextDistance = Float.POSITIVE_INFINITY
        layout.faces.forEach { button ->
            val bounds = layout.buttons[button] ?: return@forEach
            val x = (position.x - bounds.center.x) / bounds.width
            val y = (position.y - bounds.center.y) / bounds.height
            val squared = x * x + y * y
            if (squared < distance) {
                next = nearest; nextDistance = distance
                nearest = button; distance = squared
            } else if (squared < nextDistance) {
                next = button; nextDistance = squared
            }
        }
        return buildSet {
            nearest?.takeIf { distance <= .64f }?.let(::add)
            next?.takeIf { nextDistance <= .64f && nextDistance <= distance + .15f }?.let(::add)
        }
    }
}

/** Angular sectors keep cardinal/diagonal directions consistent at every thumb distance. */
internal fun touchDpadDirections(position: Offset, size: IntSize): Set<RetroPadButton> =
    dpadDirections(position, Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()))

private fun dpadDirections(position: Offset, bounds: Rect, diagonalBoundary: Float = .41421356f): Set<RetroPadButton> = eightWayButtons(
    position, bounds, deadzone = .22f, diagonalBoundary = diagonalBoundary,
    up = RetroPadButton.UP, down = RetroPadButton.DOWN,
    left = RetroPadButton.LEFT, right = RetroPadButton.RIGHT,
)

private fun eightWayButtons(
    position: Offset,
    bounds: Rect,
    deadzone: Float,
    up: RetroPadButton,
    down: RetroPadButton,
    left: RetroPadButton,
    right: RetroPadButton,
    diagonalBoundary: Float = .41421356f,
): Set<RetroPadButton> {
    if (bounds.width <= 0f || bounds.height <= 0f) return emptySet()
    val x = (position.x - bounds.center.x) / (bounds.width / 2f)
    val y = (position.y - bounds.center.y) / (bounds.height / 2f)
    if (hypot(x, y) <= deadzone) return emptySet()
    // tan(22.5 degrees): four cardinal and four diagonal sectors, each 45 degrees wide.
    return buildSet {
        if (abs(x) >= abs(y) * diagonalBoundary) add(if (x < 0f) left else right)
        if (abs(y) >= abs(x) * diagonalBoundary) add(if (y < 0f) up else down)
    }
}

/** A small radial deadzone prevents center jitter; the remaining travel spans the full range. */
internal fun touchStickAxes(position: Offset, size: IntSize): Offset =
    stickAxes(position, Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()))

private fun stickAxes(position: Offset, bounds: Rect, deadzone: Float = .12f): Offset {
    if (bounds.width <= 0f || bounds.height <= 0f) return Offset.Zero
    val x = (position.x - bounds.center.x) / (bounds.width * .42f)
    val y = (position.y - bounds.center.y) / (bounds.height * .42f)
    val distance = hypot(x, y)
    if (distance <= deadzone) return Offset.Zero
    val strength = (distance.coerceAtMost(1f) - deadzone) / (1f - deadzone)
    return Offset(x / distance * strength, y / distance * strength)
}
