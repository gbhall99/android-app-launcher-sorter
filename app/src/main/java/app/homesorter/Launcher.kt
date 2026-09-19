package app.homesorter

class StopException : RuntimeException("Stopped")
class DriveException(msg: String, val missing: String? = null) : RuntimeException(msg)

data class HomeItem(val label: String, val page: Int)
data class HomeFolder(val name: String, val apps: List<String>)
data class Inventory(val loose: List<HomeItem>, val folders: List<HomeFolder>, val dock: List<String>, val pages: Int)

/**
 * Drives the home app through its own accessibility actions, as verified against AOSP Launcher3
 * (which Pixel Launcher is built on): "Move item" picks an icon up; each grid cell then appears as a
 * virtual node described "Create folder with: X" / "Add to folder: Y"; clicking one drops the icon.
 * If the launcher exposes no Move action, it falls back to a physical drag (same page only).
 */
class Launcher(private val dev: Device, private val say: (String) -> Unit) {
    private val s = LauncherStrings(dev::launcherString)
    @Volatile var stopped = false

    private class Seen(val node: UiNode, val label: String, val folder: Boolean, val bounds: Box, val dock: Boolean, val desc: String?)

    fun note(msg: String) = say(msg)

    // ------------------------------------------------------------------ public operations

    fun scan(): Inventory {
        check()
        say("Scanning your home screen…")
        goHome()
        val pages = ArrayList<List<Seen>>()
        val folderApps = HashMap<String, List<String>>() // key -> contents
        var lastSig: String? = null
        while (pages.size < 12) {
            val its = items()
            val sig = its.joinToString("|") { key(it) }
            if (sig == lastSig) break // the launcher ignored the scroll, so this was the last page
            lastSig = sig
            pages += its
            for (f in its.filter { it.folder }) folderApps[pages.size.toString() + key(f)] = readFolder(f)
            say("Scanned page ${pages.size}")
            if (!scrollPage(true)) break
        }
        goHome()
        // Dock icons sit in the same place on every page; that catches them even if the launcher
        // hides its view ids. With one page, fall back to the hotseat id / bottom-band check.
        val onEvery = if (pages.size > 1) pages.map { p -> p.map(::key).toSet() }.reduce { a, b -> a intersect b } else emptySet()
        val loose = ArrayList<HomeItem>(); val folders = ArrayList<HomeFolder>(); val dock = LinkedHashSet<String>()
        pages.forEachIndexed { i, its ->
            for (it in its) {
                val contents = folderApps[(i + 1).toString() + key(it)].orEmpty()
                val inDock = it.dock || key(it) in onEvery
                when {
                    inDock && i > 0 -> Unit
                    inDock -> { dock += it.label; dock += contents }
                    it.folder -> folders += HomeFolder(it.label, contents)
                    else -> loose += HomeItem(it.label, i)
                }
            }
        }
        return Inventory(loose, folders, dock.toList(), pages.size.coerceAtLeast(1))
    }

    fun createFolder(anchor: String, second: String, name: String) {
        say("Creating “$name”: $second + $anchor")
        findOnPages(anchor) { !it.folder && it.label.equals(anchor, true) } // fail early if the anchor is gone
        moveOnto(second, anchor) { d -> s.createTitle(d)?.equals(anchor, true) == true }
        nameNewFolder(setOf(anchor, second), name)
    }

    fun addToFolder(app: String, folder: String) {
        say("Adding $app to “$folder”")
        moveOnto(app, folder) { d -> s.addTitle(d)?.equals(folder, true) == true }
    }

    fun addFromDrawer(label: String) {
        say("Adding $label from the app drawer")
        goHome()
        var list: UiNode? = null
        for (attempt in 0 until 2) {
            val (w, h) = dev.screen()
            dev.swipe(w / 2f, h * 0.72f, w / 2f, h * 0.25f, 280)
            pause(900)
            list = waitFor(2000) { recycler() }
            if (list != null) break
            goHome()
        }
        if (list == null) throw DriveException("the app drawer didn't open")
        var guard = 0
        while (guard++ < 60 && list.perform(Ax.SCROLL_BACKWARD)) pause(120)
        pause(300)
        for (i in 0 until 120) {
            val hit = nodes().firstOrNull { it.visible && it.name().equals(label, true) && it.act(s.addToHome) != null }
            if (hit != null) {
                hit.perform(hit.act(s.addToHome)!!.id)
                pause(1800)
                goHome()
                return
            }
            if (!list.perform(Ax.SCROLL_FORWARD)) break
            pause(350)
        }
        goHome()
        throw DriveException("“$label” wasn't found in the app drawer", missing = label)
    }

    /** Dumps what the launcher exposes, normally and mid-move, so failures can be diagnosed remotely. */
    fun diagnose(): String {
        val sb = StringBuilder()
        sb.appendLine("Home Sorter diagnostics · ${dev.describe()}")
        sb.appendLine(s.describe())
        goHome()
        sb.appendLine("\n=== Home screen ===")
        dump(sb)
        val first = items().firstOrNull { !it.folder && !it.dock }
        val move = first?.node?.act(s.move)
        if (first != null && move != null) {
            first.node.perform(move.id)
            pause(1000)
            sb.appendLine("\n=== While moving “${first.label}” ===")
            dump(sb)
            dev.global(Ax.BACK) // cancels the move; the icon goes back to its spot
            pause(1000)
        } else sb.appendLine("\n(no icon exposed a Move action — the drag fallback would be used)")
        goHome()
        return sb.toString()
    }

    fun goHome() {
        dev.global(Ax.HOME); pause(900)
        dev.global(Ax.HOME); pause(900) // a second press returns to the first page
        waitFor { dev.launcherRoot() } ?: throw DriveException("the launcher didn't come to the front")
    }

    // ------------------------------------------------------------------ moving & folders

    private fun moveOnto(label: String, targetLabel: String, match: (String) -> Boolean) {
        val item = findOnPages(label) { !it.folder && it.label.equals(label, true) }
        val move = item.node.act(s.move)
        if (move != null && item.node.perform(move.id)) {
            pause(900)
            val drop = findDrop(match)
            if (drop == null) {
                dev.global(Ax.BACK); pause(900) // cancel: the icon returns to where it was
                throw DriveException("couldn't find “$targetLabel” to drop onto", missing = targetLabel)
            }
            drop.perform(Ax.CLICK)
            pause(1300)
            return
        }
        val target = items().firstOrNull { !it.dock && it.label.equals(targetLabel, true) }
            ?: throw DriveException("“$targetLabel” must be on the same page as “$label” for the drag fallback")
        if (!dev.drag(item.bounds, target.bounds)) throw DriveException("the drag gesture was cancelled")
        pause(1300)
    }

    /** While an icon is picked up, each cell is a virtual node; the dock's cells are ignored. */
    private fun findDrop(match: (String) -> Boolean): UiNode? {
        fun here(visibleOnly: Boolean) = nodes().filter { n ->
            (!visibleOnly || n.visible) && n.desc?.let(match) == true &&
                n.parent?.viewId?.endsWith(":id/hotseat") != true
        }.minByOrNull { it.bounds.cy } // the workspace sits above the dock
        here(true)?.let { return it }
        var guard = 0
        while (guard++ < 12 && scrollPage(false)) Unit
        for (i in 0 until 12) {
            here(true)?.let { return it }
            if (!scrollPage(true)) break
        }
        return here(false)
    }

    private fun nameNewFolder(members: Set<String>, name: String) {
        val want = members.map { it.lowercase() }.toSet()
        pause(400)
        // Newest folder is most likely the one with 2 items and no name yet.
        val candidates = items().filter { it.folder && !it.dock }.sortedWith(
            compareBy<Seen>({ if (it.desc?.contains(", 2 ") == true) 0 else 1 }, { if (it.label.isBlank()) 0 else 1 })
        )
        for (f in candidates) {
            if (!f.node.perform(Ax.CLICK)) continue
            pause(800)
            val inside = openFolderItems().map { it.label.lowercase() }.toSet()
            if (inside.containsAll(want)) {
                setOpenFolderName(name)
                if (items().none { it.folder && it.label.equals(name, true) }) say("Couldn't confirm the name “$name” stuck")
                return
            }
            closeFolder()
        }
        throw DriveException("the folder was made but I couldn't find it to name it “$name”")
    }

    /**
     * Folder names are saved when the name field loses focus, on Back, or when the folder closes
     * (Launcher3 Folder.onBackKey). Tap to start editing, set the text, then clear focus.
     */
    private fun setOpenFolderName(name: String) {
        var field = waitFor { editField() } ?: throw DriveException("no name field in the folder")
        field.perform(Ax.CLICK)
        pause(600)
        field = editField() ?: field
        if (!field.perform(Ax.SET_TEXT, name)) throw DriveException("couldn't type the folder name")
        pause(300)
        field.perform(Ax.CLEAR_FOCUS)
        pause(400)
        closeFolder()
    }

    private fun readFolder(f: Seen): List<String> {
        if (!f.node.perform(Ax.CLICK)) return emptyList()
        pause(800)
        val inside = LinkedHashSet<String>()
        val pager = folderRoot()?.let { r -> nodes(r).firstOrNull { it.scrollable && it.visible } }
        for (i in 0 until 8) {
            openFolderItems().forEach { inside += it.label }
            if (pager == null || !pager.perform(Ax.SCROLL_FORWARD)) break
            pause(600)
        }
        closeFolder()
        return inside.toList()
    }

    /** Launcher3 layout: Folder > [FolderPagedView, footer > name field]. So the field's grandparent is the folder. */
    private fun folderRoot(): UiNode? = editField()?.parent?.parent

    /** Icons inside the open folder only, never the home screen behind it (or a same-named dock icon). */
    private fun openFolderItems(): List<Seen> = folderRoot()?.let { r -> items(nodes(r)).filter { !it.folder } }.orEmpty()

    private fun closeFolder() {
        for (i in 0 until 3) {
            if (editField() == null) return
            dev.global(Ax.BACK); pause(700)
        }
    }

    // ------------------------------------------------------------------ finding things

    private fun check() {
        val p = dev.launcherPackage
        if (p.isEmpty() || p == "android") throw DriveException("no default home app is set")
    }

    private fun key(it: Seen) = "${it.label}@${it.bounds.cx},${it.bounds.cy}"

    private fun findOnPages(what: String, pred: (Seen) -> Boolean): Seen {
        fun pick() = items().filter(pred).minByOrNull { if (it.dock) 1 else 0 }
        pick()?.let { return it }
        goHome()
        for (i in 0 until 12) {
            pick()?.let { return it }
            if (!scrollPage(true)) break
        }
        throw DriveException("couldn't find “$what” on the home screen", missing = what)
    }

    private fun items(all: List<UiNode> = nodes()): List<Seen> {
        val height = dev.screen().second
        val dockBox = all.firstOrNull { it.viewId?.endsWith(":id/hotseat") == true }?.bounds
        val anyMove = all.any { it.act(s.move) != null }
        return all.mapNotNull { n ->
            if (!n.visible) return@mapNotNull null
            val movable = if (anyMove) n.act(s.move) != null else n.actions.any { it.id == Ax.LONG_CLICK }
            if (!movable) return@mapNotNull null
            val folderTitle = n.desc?.let(s::folderTitle)
            val isApp = folderTitle == null && n.className?.endsWith("TextView") == true
            if (folderTitle == null && !isApp) return@mapNotNull null
            val label = folderTitle ?: n.name() ?: return@mapNotNull null
            val b = n.bounds
            val inDock = dockBox?.contains(b.cx, b.cy) ?: (b.cy > height * 0.8)
            Seen(n, label, folderTitle != null, b, inDock, n.desc)
        }
    }

    private fun nodes(from: UiNode? = dev.launcherRoot()): List<UiNode> {
        val out = ArrayList<UiNode>()
        val stack = ArrayDeque<UiNode>()
        from?.let(stack::add)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            out += n
            n.children.asReversed().forEach(stack::add)
        }
        return out
    }

    private fun editField() = nodes().firstOrNull { it.visible && (it.editable || it.className?.contains("EditText") == true) }

    private fun recycler() = nodes().filter { it.visible && it.scrollable && it.className?.contains("RecyclerView") == true }
        .maxByOrNull { it.bounds.area() }

    /** The workspace pager is the biggest scrollable thing that isn't a list. */
    private fun scrollPage(forward: Boolean): Boolean {
        val pager = nodes().filter { it.visible && it.scrollable && it.className?.contains("RecyclerView") != true }
            .maxByOrNull { it.bounds.area() } ?: return false
        val id = if (forward) Ax.SCROLL_FORWARD else Ax.SCROLL_BACKWARD
        if (pager.actions.none { it.id == id } || !pager.perform(id)) return false
        pause(800)
        return true
    }

    // ------------------------------------------------------------------ plumbing

    private fun pause(ms: Long) {
        var left = ms
        while (left > 0) {
            if (stopped) throw StopException()
            val step = minOf(40L, left)
            dev.sleep(step)
            left -= step
        }
        if (stopped) throw StopException()
    }

    private fun <T : Any> waitFor(ms: Long = 3000, f: () -> T?): T? {
        val end = dev.now() + ms
        while (true) {
            f()?.let { return it }
            if (dev.now() > end) return null
            pause(150)
        }
    }

    private fun UiNode.name(): String? = (text?.takeIf { it.isNotBlank() } ?: desc)?.trim()
    private fun UiNode.act(labels: List<String>): UiAction? = actions.firstOrNull { a ->
        val l = a.label ?: return@firstOrNull false
        labels.any { it.equals(l, ignoreCase = true) }
    }

    private fun dump(sb: StringBuilder) {
        val r = dev.launcherRoot() ?: run { sb.appendLine("(launcher window not found)"); return }
        fun rec(n: UiNode, depth: Int) {
            if (depth > 40) return
            sb.append("  ".repeat(depth)).append(n.className?.substringAfterLast('.'))
            n.viewId?.let { sb.append(" #").append(it.substringAfter('/')) }
            n.text?.let { sb.append(" t=\"").append(it).append('"') }
            n.desc?.let { sb.append(" d=\"").append(it).append('"') }
            if (!n.visible) sb.append(" hidden")
            if (n.scrollable) sb.append(" scrollable")
            if (n.editable) sb.append(" editable")
            val b = n.bounds
            sb.append(" [${b.left},${b.top}][${b.right},${b.bottom}]")
            val acts = n.actions.mapNotNull { a ->
                a.label ?: when (a.id) {
                    Ax.CLICK -> "click"; Ax.LONG_CLICK -> "longClick"
                    Ax.SCROLL_FORWARD -> "scrollFwd"; Ax.SCROLL_BACKWARD -> "scrollBack"
                    else -> null
                }
            }
            if (acts.isNotEmpty()) sb.append(" {").append(acts.joinToString(", ")).append('}')
            sb.appendLine()
            n.children.forEach { rec(it, depth + 1) }
        }
        rec(r, 0)
    }
}

/**
 * The launcher's own UI strings (so matching works in any language and survives rewording),
 * with AOSP Launcher3's English text as a fallback.
 */
internal class LauncherStrings(lookup: (String) -> String?) {
    private val found = mutableListOf<String>()
    private val get = { name: String, fallback: String ->
        val v = runCatching { lookup(name) }.getOrNull()
        found += "$name = ${v ?: "(not exposed, using) $fallback"}"
        listOfNotNull(v, fallback).distinct()
    }

    val move = get("action_move", "Move item")
    val addToHome = get("action_add_to_workspace", "Add to home screen")
    private val create = get("create_folder_with", "Create folder with: %1\$s").map(::launcherPattern)
    private val addTo = get("add_to_folder", "Add to folder: %1\$s").map(::launcherPattern)
    private val folder = (get("folder_name_format_exact", "Folder: %1\$s, %2\$d items") +
        get("folder_name_format_overflow", "Folder: %1\$s, %2\$d or more items")).map(::launcherPattern) +
        Regex("^Folder:\\s*(.*?)(?:,\\s*\\d+.*)?$", RegexOption.IGNORE_CASE)

    fun createTitle(d: String) = first(create, d)
    fun addTitle(d: String) = first(addTo, d)
    fun folderTitle(d: String) = first(folder, d)
    fun describe() = found.joinToString("\n")

    private fun first(ps: List<Regex>, d: String) =
        ps.firstNotNullOfOrNull { it.matchEntire(d.trim())?.groupValues?.getOrNull(1)?.trim() }
}

/** Turns a launcher format string like "Add to folder: %1\$s" into a regex capturing the name. */
internal fun launcherPattern(fmt: String): Regex {
    val sb = StringBuilder("^")
    var last = 0
    Regex("%(\\d+\\$)?([sd])").findAll(fmt).forEach { m ->
        sb.append(Regex.escape(fmt.substring(last, m.range.first)))
        sb.append(if (m.groupValues[2] == "s") "(.*?)" else "\\d+")
        last = m.range.last + 1
    }
    sb.append(Regex.escape(fmt.substring(last))).append('$')
    return Regex(sb.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
}
