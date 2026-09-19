package app.homesorter

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

class SorterService : AccessibilityService() {
    companion object { @Volatile var instance: SorterService? = null }

    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private var banner: View? = null
    private var bannerText: TextView? = null
    lateinit var launcher: Launcher
        private set

    override fun onServiceConnected() {
        launcher = Launcher(AndroidDevice(this)) { msg -> Store.log(msg); main.post { bannerText?.text = msg } }
        instance = this
        Store.post { Store.serviceOn.value = true }
    }

    override fun onDestroy() {
        instance = null
        if (::launcher.isInitialized) launcher.stopped = true
        Store.post { Store.serviceOn.value = false; Store.busy.value = false }
        hideBanner()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Runs [job] off the main thread with a floating status banner and a STOP control. */
    fun start(title: String, returnToApp: Boolean, job: (Launcher) -> Unit) {
        if (worker?.isAlive == true) return
        launcher.stopped = false
        Store.post { Store.busy.value = true }
        main.post { showBanner(title) }
        worker = thread(name = "home-sorter") {
            try {
                job(launcher)
            } catch (e: StopException) {
                launcher.note("$title stopped")
            } catch (e: Exception) {
                launcher.note("$title failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                main.post {
                    Store.busy.value = false
                    if (returnToApp) {
                        hideBanner()
                        startActivity(Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    } else main.postDelayed({ hideBanner() }, 5000)
                }
            }
        }
    }

    private fun showBanner(title: String) {
        hideBanner()
        val dp = resources.displayMetrics.density
        val label = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; maxLines = 2; text = title
        }
        val stop = TextView(this).apply {
            text = "STOP"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFB4AB.toInt()); setPadding((14 * dp).toInt(), 0, 0, 0)
            setOnClickListener { launcher.stopped = true; bannerText?.text = "Stopping…" }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = 24 * dp; setColor(0xE6202124.toInt()) }
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(stop)
        }
        val lp = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels - 32 * dp).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (36 * dp).toInt() }
        runCatching { getSystemService(WindowManager::class.java).addView(box, lp); banner = box; bannerText = label }
    }

    private fun hideBanner() {
        banner?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }
        banner = null; bannerText = null
    }
}
