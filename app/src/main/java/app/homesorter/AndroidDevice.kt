package app.homesorter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Thin adapter from the accessibility framework to [Device]. Deliberately logic-free. */
class AndroidDevice(private val svc: AccessibilityService) : Device {
    override val ownPackage: String = svc.packageName

    @Volatile private var chosen: String? = null
    @Volatile private var resolved: String? = null
    @Volatile private var res: Pair<String, Resources?>? = null

    /**
     * The default home app. Since Android 11 the system only names an app the manifest's <queries>
     * covers; when it can't, it answers with nothing, the chooser ("android") or Settings' emergency
     * FallbackHome, none of which is the launcher, so those count as "unknown" and are asked again next
     * time. [useLauncher] overrides the answer with whatever the Home button really opened.
     */
    override val launcherPackage: String
        get() = chosen ?: resolved ?: runCatching {
            svc.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        }.getOrNull()?.takeUnless { it == "android" || it == "com.android.settings" || it == ownPackage }
            .also { resolved = it }.orEmpty()

    override fun useLauncher(pkg: String) { chosen = pkg }

    override fun launcherString(name: String): String? {
        val pkg = launcherPackage
        if (pkg.isEmpty()) return null
        val r = res?.takeIf { it.first == pkg }?.second
            ?: runCatching { svc.packageManager.getResourcesForApplication(pkg) }.getOrNull().also { res = pkg to it }
        return r?.let { r -> r.getIdentifier(name, "string", pkg).takeIf { it != 0 }?.let { runCatching { r.getString(it) }.getOrNull() } }
    }

    override fun launcherRoot(): UiNode? {
        val pkg = launcherPackage
        if (pkg.isEmpty()) return null
        val w = svc.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() == pkg }
        val root = w?.root ?: svc.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }
        return root?.let { AndroidNode(it, null) }
    }

    override fun frontApp(): String? {
        val apps = svc.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { w -> w.root?.packageName?.toString()?.takeIf { it != ownPackage }?.let { w to it } }
        val (_, pkg) = apps.firstOrNull { it.first.isActive } ?: apps.firstOrNull { it.first.isFocused }
            ?: apps.maxByOrNull { it.first.layer } ?: return null
        return pkg
    }

    override fun windows(): String = svc.windows.joinToString(" ") { w ->
        val type = when (w.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
            else -> "type${w.type}"
        }
        "[$type ${w.root?.packageName ?: w.title ?: "?"}${if (w.isActive) " active" else ""}]"
    }.ifEmpty { "(no windows reported)" }

    override fun global(action: Int) = svc.performGlobalAction(action)

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) =
        dispatch(GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, ms))

    override fun drag(from: Box, to: Box): Boolean {
        val ax = from.cx.toFloat(); val ay = from.cy.toFloat(); val bx = to.cx.toFloat(); val by = to.cy.toFloat()
        val hold = GestureDescription.StrokeDescription(Path().apply { moveTo(ax, ay); lineTo(ax + 1, ay + 1) }, 0, 900, true)
        val travel = hold.continueStroke(Path().apply { moveTo(ax + 1, ay + 1); lineTo(bx, by) }, 0, 600, true)
        val drop = travel.continueStroke(Path().apply { moveTo(bx, by); lineTo(bx + 1, by + 1) }, 0, 1000, false)
        return dispatch(hold) && dispatch(travel) && dispatch(drop)
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
        }, null)
        latch.await(6, TimeUnit.SECONDS)
        return ok
    }

    override fun screen(): Pair<Int, Int> = runCatching {
        svc.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.let { it.width() to it.height() }
    }.getOrElse { svc.resources.displayMetrics.let { it.widthPixels to it.heightPixels } }

    override fun now() = SystemClock.uptimeMillis()
    override fun sleep(ms: Long) = Thread.sleep(ms)

    override fun describe(): String {
        val ver = runCatching { svc.packageManager.getPackageInfo(launcherPackage, 0).versionName }.getOrNull()
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · $launcherPackage $ver"
    }
}

private class AndroidNode(private val n: AccessibilityNodeInfo, private val up: UiNode?) : UiNode {
    override val className get() = n.className?.toString()
    override val text get() = n.text?.toString()
    override val desc get() = n.contentDescription?.toString()
    override val viewId: String? get() = n.viewIdResourceName
    override val visible get() = n.isVisibleToUser
    override val scrollable get() = n.isScrollable
    override val editable get() = n.isEditable
    override val bounds get() = Rect().also { n.getBoundsInScreen(it) }.let { Box(it.left, it.top, it.right, it.bottom) }
    override val actions get() = n.actionList.map { UiAction(it.id, it.label?.toString()) }
    override val children get() = (0 until n.childCount).mapNotNull { i -> n.getChild(i)?.let { AndroidNode(it, this) } }
    override val parent get() = up ?: n.parent?.let { AndroidNode(it, null) }
    override fun perform(action: Int, setText: String?): Boolean =
        if (setText == null) n.performAction(action)
        else n.performAction(action, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, setText) })
}
