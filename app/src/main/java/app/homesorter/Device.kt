package app.homesorter

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

/** Plain-Kotlin view of the screen, so the driver can be tested against a simulated launcher. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2
    fun area() = (right - left).toLong() * (bottom - top)
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
}

data class UiAction(val id: Int, val label: String?)

interface UiNode {
    val className: String?
    val text: String?
    val desc: String?
    val viewId: String?
    val visible: Boolean
    val scrollable: Boolean
    val editable: Boolean
    val bounds: Box
    val actions: List<UiAction>
    val children: List<UiNode>
    val parent: UiNode?
    fun perform(action: Int, setText: String? = null): Boolean
}

interface Device {
    val launcherPackage: String
    fun launcherString(name: String): String?
    fun launcherRoot(): UiNode?
    fun global(action: Int): Boolean
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean
    fun drag(from: Box, to: Box): Boolean
    fun screen(): Pair<Int, Int>
    fun now(): Long
    fun sleep(ms: Long)
    fun describe(): String
}

/** Android's own constant values, inlined at compile time. */
object Ax {
    const val FOCUS = AccessibilityNodeInfo.ACTION_FOCUS
    const val CLEAR_FOCUS = AccessibilityNodeInfo.ACTION_CLEAR_FOCUS
    const val CLICK = AccessibilityNodeInfo.ACTION_CLICK
    const val LONG_CLICK = AccessibilityNodeInfo.ACTION_LONG_CLICK
    const val SCROLL_FORWARD = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    const val SCROLL_BACKWARD = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    const val SET_TEXT = AccessibilityNodeInfo.ACTION_SET_TEXT
    const val BACK = AccessibilityService.GLOBAL_ACTION_BACK
    const val HOME = AccessibilityService.GLOBAL_ACTION_HOME
}
