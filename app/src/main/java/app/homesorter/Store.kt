package app.homesorter

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** One app in the plan. Home-screen apps start ticked; app-drawer suggestions start unticked. */
class PlanApp(val label: String, val fromDrawer: Boolean, on: Boolean) {
    var on by mutableStateOf(on)
}

/** A folder to create (or an existing one to fill). The "Not sorted" bucket is never executed. */
class Group(name: String, val existing: Boolean, val unsorted: Boolean = false) {
    val id = ids.incrementAndGet()
    var name by mutableStateOf(name)
    val apps = mutableStateListOf<PlanApp>()
    fun display() = if (unsorted) "Not sorted (leave loose)" else name.ifBlank { "Untitled" }
    private companion object { val ids = AtomicLong() }
}

/** App-wide UI state. All writes go through the main thread. */
object Store {
    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.UK)
    val serviceOn = mutableStateOf(false)
    val busy = mutableStateOf(false)
    val status = mutableStateOf("")
    val inventory = mutableStateOf<Inventory?>(null)
    val groups = mutableStateListOf<Group>()
    val lines = mutableStateListOf<String>()
    @Volatile var lastDump = ""

    fun post(f: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) f() else main.post(f) }

    fun log(msg: String) = post {
        status.value = msg
        lines.add("${clock.format(Date())}  $msg")
        if (lines.size > 300) lines.removeRange(0, lines.size - 300)
    }
}
