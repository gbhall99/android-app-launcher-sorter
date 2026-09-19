package app.homesorter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = when {
                android.os.Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) { App() }
        }
    }

    override fun onResume() {
        super.onResume()
        Store.serviceOn.value = SorterService.instance != null
    }
}

// ---------------------------------------------------------------- actions

private fun scan() {
    val svc = SorterService.instance ?: return
    svc.start("Scanning", returnToApp = true) { l ->
        val inv = l.scan()
        val groups = Planner.build(inv, Apps.installed(svc))
        Store.post { Store.inventory.value = inv; Store.groups.clear(); Store.groups.addAll(groups) }
        l.note("Found ${inv.loose.size} loose icons on ${inv.pages} page(s) and ${inv.folders.size} folder(s)")
    }
}

private fun sort(test: Boolean) {
    val svc = SorterService.instance ?: return
    val jobs = Planner.jobs(Store.groups).let { if (test) it.take(1) else it }
    if (jobs.isEmpty()) { Store.log("Nothing to do: tick at least 2 apps in a new folder"); return }
    svc.start(if (test) "Test run" else "Sorting", returnToApp = false) { l ->
        Executor.run(l, jobs).problems.forEach { Store.log("  ✗ $it") }
    }
}

private fun diagnose() {
    val svc = SorterService.instance ?: return
    svc.start("Diagnosing", returnToApp = true) { l ->
        Store.lastDump = l.diagnose()
        l.note("Diagnostics captured (${Store.lastDump.length / 1024} KB). Tap Share.")
    }
}

/** The installed version, so logs and the screen always say which build produced them. */
fun version(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

private fun share(ctx: Context) {
    val f = File(ctx.cacheDir, "home-sorter-diagnostics.txt")
    f.writeText("Home Sorter ${version(ctx)}\n\nLOG\n" + Store.lines.joinToString("\n") + "\n\n" + Store.lastDump)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share diagnostics"))
}

private fun addFolder() {
    val at = Store.groups.indexOfFirst { it.unsorted }.let { if (it < 0) Store.groups.size else it }
    Store.groups.add(at, Group("New folder", existing = false))
}

// ---------------------------------------------------------------- UI

@Composable
private fun App() {
    val on by Store.serviceOn
    val busy by Store.busy
    val inv by Store.inventory
    Scaffold { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Header(busy) }
            item { SetupCard(on) }
            item { ScanCard(on, busy, inv) }
            if (inv != null) {
                items(Store.groups, key = { it.id }) { g -> GroupCard(g) }
                item {
                    OutlinedButton(onClick = ::addFolder, modifier = Modifier.fillMaxWidth()) { Text("+ New folder") }
                }
                item { RunCard(on, busy) }
            }
            item { LogCard(on, busy) }
        }
    }
}

@Composable
private fun Header(busy: Boolean) {
    val status by Store.status
    val ctx = LocalContext.current
    Column {
        Text("Home Sorter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("Version ${version(ctx)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Sorts your home screen icons into folders using the launcher's own accessibility controls.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (busy) { Spacer(Modifier.height(10.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (status.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(status, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun StepCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SetupCard(on: Boolean) {
    val ctx = LocalContext.current
    StepCard(if (on) "1 · Access is on ✓" else "1 · Switch on access") {
        if (on) {
            Hint("You can switch it off again in Accessibility settings once you're happy with your home screen.")
            return@StepCard
        }
        Text("Home Sorter needs its accessibility service to read the home screen and move icons. It only acts when you press a button here.")
        Hint("Sideloaded apps are blocked at first. Tap the greyed-out switch once, then App info › ⋮ (top right) › Allow restricted settings, and switch it on.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility") }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("App info") }
        }
    }
}

@Composable
private fun ScanCard(on: Boolean, busy: Boolean, inv: Inventory?) {
    StepCard("2 · Scan your home screen") {
        if (inv == null) Text("Visits each home screen page and peeks inside your folders. Nothing is moved.")
        else Text("${inv.loose.size} loose icons · ${inv.pages} page(s) · ${inv.folders.size} folder(s) · dock (${inv.dock.size}) left alone")
        Button(onClick = ::scan, enabled = on && !busy) { Text(if (inv == null) "Scan" else "Rescan") }
    }
}

@Composable
private fun GroupCard(g: Group) {
    val others = Store.groups.filter { it !== g }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (g.unsorted) {
                Text("Not sorted", style = MaterialTheme.typography.titleMedium)
                Hint("No obvious home for these. Move any into a folder, or leave them loose.")
            } else {
                OutlinedTextField(
                    value = g.name, onValueChange = { g.name = it }, singleLine = true, enabled = !g.existing,
                    label = { Text(if (g.existing) "Existing folder" else "Folder name") },
                    modifier = Modifier.fillMaxWidth()
                )
                val picked = g.apps.count { it.on }
                if (!g.existing && picked < 2) {
                    Text("Tick at least 2 apps or this folder is skipped", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            if (g.apps.isEmpty()) Hint("Empty: move apps here with their Move button.")
            g.apps.toList().forEach { a -> key(a) { AppRow(a, g, others) } }
        }
    }
}

@Composable
private fun AppRow(a: PlanApp, g: Group, others: List<Group>) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (!g.unsorted) Checkbox(checked = a.on, onCheckedChange = { a.on = it })
        Column(Modifier.weight(1f).padding(start = if (g.unsorted) 4.dp else 0.dp)) {
            Text(a.label, style = MaterialTheme.typography.bodyLarge)
            if (a.fromDrawer) Hint("In app drawer · tick to add it here")
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("Move") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                others.forEach { o ->
                    DropdownMenuItem(text = { Text(o.display()) }, onClick = {
                        menu = false
                        g.apps.remove(a); o.apps.add(a)
                        if (!o.unsorted && !a.fromDrawer) a.on = true
                    })
                }
            }
        }
    }
}

@Composable
private fun RunCard(on: Boolean, busy: Boolean) {
    val live = Store.groups.filter { !it.unsorted }
    val fresh = live.count { !it.existing && it.apps.count { a -> a.on } >= 2 }
    val fill = live.count { it.existing && it.apps.any { a -> a.on } }
    val drawer = live.sumOf { it.apps.count { a -> a.on && a.fromDrawer } }
    StepCard("3 · Sort") {
        Text("Will create $fresh folder(s), add to $fill existing folder(s), and bring $drawer app(s) in from the drawer.")
        Hint("Screenshot your home screen first so you can put things back. Then leave the phone alone while it works; tap STOP on the banner to halt. Try the test run first.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { sort(test = true) }, enabled = on && !busy) { Text("Test: first folder") }
            Button(onClick = { sort(test = false) }, enabled = on && !busy) { Text("Sort all") }
        }
    }
}

@Composable
private fun LogCard(on: Boolean, busy: Boolean) {
    val ctx = LocalContext.current
    StepCard("Log & diagnostics") {
        val recent = Store.lines.takeLast(12)
        if (recent.isEmpty()) Hint("Nothing yet.")
        recent.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Hint("If something doesn't work, run Diagnose (it picks up one icon for a second and puts it back), then Share the file.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ::diagnose, enabled = on && !busy) { Text("Diagnose") }
            OutlinedButton(onClick = { share(ctx) }) { Text("Share log") }
        }
    }
}
