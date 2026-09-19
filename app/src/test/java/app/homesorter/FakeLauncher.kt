package app.homesorter

/**
 * A simulated Pixel Launcher that reproduces the Launcher3 behaviour Home Sorter relies on, as read
 * from AOSP source: icon actions (LauncherAccessibilityDelegate), drop-target descriptions
 * (WorkspaceAccessibilityHelper), page scrolling (PagedView), folder isolation (DragLayer),
 * folder-name commit rules (Folder / ExtendedEditText), auto-labelling (FolderIcon) and
 * "Add to home screen" placement (findSpaceOnWorkspace).
 */
class FakeLauncher(
    pageSetup: List<List<Any?>>,              // per page, cells in reading order: String = app, Pair<String, List<String>> = folder
    dockApps: List<String>,
    private val drawer: List<String>,
    private val strings: Map<String, String>? = AOSP,
    private val hotseatId: String? = "com.google.android.apps.nexuslauncher:id/hotseat",
    private val suggest: (List<String>) -> String? = { null },
    private val dots: Map<String, Int> = emptyMap(),
    private val clearFocusWorks: Boolean = true,   // false: only Back / closing the folder commits a name
    private val moveSupported: Boolean = true,     // false: launcher without accessible drag (gesture fallback)
) : Device {
    companion object {
        val AOSP = mapOf(
            "action_move" to "Move item", "action_add_to_workspace" to "Add to home screen",
            "create_folder_with" to "Create folder with: %1\$s", "add_to_folder" to "Add to folder: %1\$s",
            "add_to_folder_with_app" to "Add to folder with %1\$s",
            "folder_name_format_exact" to "Folder: %1\$s, %2\$d items",
            "folder_name_format_overflow" to "Folder: %1\$s, %2\$d or more items",
        )
        const val MOVE = 0x7f0a0001
        const val ADD = 0x7f0a0002
        const val COLS = 5; const val ROWS = 5; const val W = 1080; const val H = 2400
    }

    abstract class Item { abstract var title: String }
    class App(override var title: String) : Item()
    class Folder(override var title: String, val apps: MutableList<App>, var labelled: Boolean) : Item()

    enum class State { NORMAL, ALL_APPS, FOLDER, DRAG }

    val pages: MutableList<MutableMap<Int, Item>> = pageSetup.map { cells ->
        val m = mutableMapOf<Int, Item>()
        cells.forEachIndexed { i, c ->
            when (c) {
                is String -> m[i] = App(c)
                is Pair<*, *> -> m[i] = Folder(c.first as String, (c.second as List<*>).map { App(it as String) }.toMutableList(), (c.first as String).isNotEmpty())
            }
        }
        m
    }.toMutableList()
    val dock: MutableList<Item> = dockApps.map<String, Item> { App(it) }.toMutableList()
    var current = 0; var state = State.NORMAL
    private var open: Folder? = null
    private var field = ""; private var focused = false; private var editing = false
    private var dragItem: Item? = null; private var dragPage = 0; private var dragCell = 0
    private var drawerTop = 0
    private var folderPage = 0
    private var clock = 0L
    val events = mutableListOf<String>()

    // ------------------------------------------------------------ inspection helpers for tests
    fun folders(): Map<String, List<String>> = pages.flatMap { it.values }.filterIsInstance<Folder>().associate { f -> f.title to f.apps.map { it.title } }
    fun loose(): List<String> = pages.flatMap { p -> p.toSortedMap().values }.filterIsInstance<App>().map { it.title }

    // ------------------------------------------------------------ Device
    override val launcherPackage = "com.google.android.apps.nexuslauncher"
    override fun launcherString(name: String) = strings?.get(name)
    override fun screen() = W to H
    override fun now() = clock
    override fun sleep(ms: Long) { clock += ms }
    override fun describe() = "FakeLauncher"
    override fun drag(from: Box, to: Box): Boolean {
        if (moveSupported || state != State.NORMAL) return false
        fun at(b: Box) = pages[current].entries.firstOrNull { (c, _) -> cellBox(current, c).contains(b.cx, b.cy) }
        val src = at(from) ?: return false
        val dst = at(to) ?: return false
        startDrag(src.value, current, src.key)
        return drop(current, dst.key)
    }

    override fun global(action: Int): Boolean {
        events += "global:$action/$state"
        when (action) {
            Ax.BACK -> when (state) {
                State.DRAG -> cancelDrag()
                State.ALL_APPS -> state = State.NORMAL
                State.FOLDER -> if (focused && editing) commitName() else closeFolder()
                State.NORMAL -> Unit
            }
            Ax.HOME -> when (state) {
                State.NORMAL -> current = 0
                State.DRAG -> cancelDrag()
                State.FOLDER -> closeFolder()
                State.ALL_APPS -> state = State.NORMAL
            }
        }
        return true
    }

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        if (state == State.NORMAL && y1 - y2 > H * 0.2f) { state = State.ALL_APPS; drawerTop = 3 }
        return true
    }

    private fun cancelDrag() { state = State.NORMAL; dragItem = null }
    private fun commitName() { open!!.title = field; open!!.labelled = true; editing = false; focused = false }
    private fun closeFolder() { if (editing) commitName(); open = null; focused = false; state = State.NORMAL }

    // ------------------------------------------------------------ tree
    inner class N(
        override val className: String?, override val text: String? = null, override val desc: String? = null,
        override val viewId: String? = null, override val visible: Boolean = true, override val scrollable: Boolean = false,
        override val editable: Boolean = false, override val bounds: Box = Box(0, 0, W, H),
        override val actions: List<UiAction> = emptyList(), private val onAction: (Int, String?) -> Boolean = { _, _ -> false },
    ) : UiNode {
        override val children = mutableListOf<UiNode>()
        override var parent: UiNode? = null
        fun add(c: N): N { children += c; c.parent = this; return c }
        override fun perform(action: Int, setText: String?): Boolean =
            if (actions.none { it.id == action }) false else onAction(action, setText)
    }

    private fun cellBox(page: Int, cell: Int): Box {
        val x = (page - current) * W + (cell % COLS) * (W / COLS); val y = 300 + (cell / COLS) * 300
        return Box(x, y, x + W / COLS, y + 300)
    }

    private fun appNode(a: App, box: Box, visible: Boolean, extra: List<UiAction> = emptyList(), onAct: (Int) -> Boolean): N {
        val d = dots[a.title]?.let { "${a.title}, $it notifications" } ?: a.title
        return N("android.widget.TextView", text = a.title, desc = d, visible = visible, bounds = box,
            actions = listOf(UiAction(Ax.CLICK, null), UiAction(Ax.LONG_CLICK, null)) + extra) { id, _ -> onAct(id) }
    }

    private fun folderDesc(f: Folder) =
        if (f.apps.size < 4) "Folder: ${f.title}, ${f.apps.size} items" else "Folder: ${f.title}, 4 or more items"

    private fun moveAct() = if (!moveSupported) emptyList() else listOf(UiAction(MOVE, strings?.get("action_move") ?: "Move item"))

    private fun dropDesc(child: Item?, p: Int, cell: Int) = when {
        child == null || child === dragItem -> "Move to row ${cell / COLS + 1} column ${cell % COLS + 1} in Home screen ${p + 1} of ${pages.size}"
        child is App -> strings.fmt("create_folder_with", "Create folder with: %1\$s", child.title)
        child is Folder && child.title.isEmpty() -> strings.fmt("add_to_folder_with_app", "Add to folder with %1\$s", child.apps.first().title)
        else -> strings.fmt("add_to_folder", "Add to folder: %1\$s", (child as Folder).title)
    }

    override fun launcherRoot(): UiNode {
        val root = N("android.widget.FrameLayout")
        when (state) {
            State.NORMAL, State.DRAG -> {
                val ws = root.add(N("android.widget.ScrollView", scrollable = true, bounds = Box(0, 250, W, 1850),
                    actions = listOfNotNull(
                        if (current < pages.size - 1) UiAction(Ax.SCROLL_FORWARD, null) else null,
                        if (current > 0) UiAction(Ax.SCROLL_BACKWARD, null) else null)) { id, _ ->
                    if (id == Ax.SCROLL_FORWARD && current < pages.size - 1) { current++; true }
                    else if (id == Ax.SCROLL_BACKWARD && current > 0) { current--; true } else false
                })
                pages.forEachIndexed { p, cells ->
                    val cl = ws.add(N("android.view.ViewGroup", visible = p == current, bounds = Box((p - current) * W, 300, (p - current + 1) * W, 1800)))
                    if (state == State.NORMAL) {
                        cells.toSortedMap().forEach { (cell, item) ->
                            val box = cellBox(p, cell); val vis = p == current
                            when (item) {
                                is App -> cl.add(appNode(item, box, vis, moveAct()) { id -> if (id == MOVE) startDrag(item, p, cell) else true })
                                is Folder -> cl.add(N("android.widget.FrameLayout", desc = folderDesc(item), visible = vis, bounds = box,
                                    actions = listOf(UiAction(Ax.CLICK, null), UiAction(Ax.LONG_CLICK, null)) + moveAct()) { id, _ ->
                                    when (id) { Ax.CLICK -> openFolder(item); MOVE -> startDrag(item, p, cell); else -> true }
                                }).add(N("android.widget.TextView", text = item.title, visible = vis, bounds = box))
                            }
                        }
                    } else {
                        for (cell in 0 until COLS * ROWS) {
                            cl.add(N(null, desc = dropDesc(cells[cell], p, cell), visible = p == current, bounds = cellBox(p, cell),
                                actions = listOf(UiAction(Ax.CLICK, null))) { _, _ -> drop(p, cell) })
                        }
                    }
                }
                // Pixel's "At a Glance" carousel: a second, smaller scrollable view on the home screen.
                if (state == State.NORMAL) root.add(N("androidx.viewpager.widget.ViewPager", scrollable = true, bounds = Box(0, 120, W, 250),
                    actions = listOf(UiAction(Ax.SCROLL_FORWARD, null))) { _, _ -> events += "smartspace-scrolled"; true })
                val hs = root.add(N("android.widget.FrameLayout", viewId = hotseatId, bounds = Box(0, 1950, W, 2150)))
                for (i in 0 until 5) {
                    val x = i * (W / 5); val box = Box(x, 1950, x + W / 5, 2150)
                    val item = dock.getOrNull(i)
                    if (state == State.DRAG) {
                        hs.add(N(null, desc = dropDesc(item, 0, i), bounds = box, actions = listOf(UiAction(Ax.CLICK, null))) { _, _ -> dropOnDock(i) })
                    } else if (item is App) {
                        hs.add(appNode(item, box, true, moveAct()) { true })
                    }
                }
            }
            State.FOLDER -> {
                val f = open!!
                val fv = root.add(N("android.widget.FrameLayout", bounds = Box(100, 600, 980, 1600)))
                val pagesInFolder = (f.apps.size + 15) / 16
                val content = fv.add(N("android.widget.ScrollView", scrollable = true, bounds = Box(100, 600, 980, 1450),
                    actions = listOfNotNull(if (folderPage < pagesInFolder - 1) UiAction(Ax.SCROLL_FORWARD, null) else null)) { id, _ ->
                    if (id == Ax.SCROLL_FORWARD && folderPage < pagesInFolder - 1) { folderPage++; true } else false
                })
                f.apps.drop(folderPage * 16).take(16).forEachIndexed { i, a ->
                    val x = 100 + (i % 4) * 220; val y = 600 + (i / 4) * 280
                    content.add(appNode(a, Box(x, y, x + 220, y + 280), true, moveAct()) { true })
                }
                val footer = fv.add(N("android.widget.LinearLayout", bounds = Box(100, 1450, 980, 1600)))
                footer.add(N("android.widget.EditText", text = field.ifEmpty { null }, editable = true, bounds = Box(100, 1450, 980, 1600),
                    actions = listOfNotNull(UiAction(Ax.FOCUS, null), UiAction(Ax.CLICK, null), UiAction(Ax.SET_TEXT, null),
                        if (focused && clearFocusWorks) UiAction(Ax.CLEAR_FOCUS, null) else null)) { id, t ->
                    when (id) {
                        Ax.FOCUS, Ax.CLICK -> { if (!focused) { focused = true; startEditing() }; true }
                        Ax.CLEAR_FOCUS -> { if (focused) commitName(); true } // Folder.onFocusChange(false) -> onBackKey
                        Ax.SET_TEXT -> { field = t ?: ""; true }
                        else -> false
                    }
                })
            }
            State.ALL_APPS -> {
                val sorted = drawer.sortedBy { it.lowercase() }
                root.add(N("android.widget.EditText", editable = true, bounds = Box(0, 150, W, 280),
                    actions = listOf(UiAction(Ax.CLICK, null), UiAction(Ax.SET_TEXT, null))) { _, _ -> events += "drawer-search-touched"; true })
                val addLbl = strings?.get("action_add_to_workspace") ?: "Add to home screen"
                if (drawerTop == 0) sorted.takeLast(4).forEachIndexed { i, label -> // "suggested apps" row
                    root.add(appNode(App(label), Box(i * 270, 280, i * 270 + 270, 300), true, listOf(UiAction(ADD, addLbl))) { id ->
                        if (id == ADD) addToWorkspace(label) else true })
                }
                val rv = root.add(N("androidx.recyclerview.widget.RecyclerView", scrollable = true, bounds = Box(0, 300, W, 2300),
                    actions = listOfNotNull(
                        if (drawerTop + 12 < sorted.size) UiAction(Ax.SCROLL_FORWARD, null) else null,
                        if (drawerTop > 0) UiAction(Ax.SCROLL_BACKWARD, null) else null)) { id, _ ->
                    if (id == Ax.SCROLL_FORWARD && drawerTop + 12 < sorted.size) { drawerTop += 6; true }
                    else if (id == Ax.SCROLL_BACKWARD && drawerTop > 0) { drawerTop = maxOf(0, drawerTop - 6); true } else false
                })
                val addLabel = strings?.get("action_add_to_workspace") ?: "Add to home screen"
                sorted.drop(drawerTop).take(12).forEachIndexed { i, label ->
                    val y = 300 + i * 160
                    rv.add(appNode(App(label), Box(0, y, W, y + 160), true, listOf(UiAction(ADD, addLabel))) { id ->
                        if (id == ADD) addToWorkspace(label) else true
                    })
                }
            }
        }
        return root
    }

    private fun Map<String, String>?.fmt(key: String, fb: String, arg: String) =
        (this?.get(key) ?: fb).replace("%1\$s", arg)

    // ------------------------------------------------------------ launcher behaviour
    private fun startDrag(item: Item, p: Int, cell: Int): Boolean {
        state = State.DRAG; dragItem = item; dragPage = p; dragCell = cell; return true
    }

    private fun drop(p: Int, cell: Int): Boolean {
        val item = dragItem ?: return false
        val target = pages[p][cell]
        pages[dragPage].remove(dragCell)
        when {
            target == null || target === item -> pages[p][cell] = item
            target is App && item is App -> {
                val f = Folder("", mutableListOf(target, item), false)
                pages[p][cell] = f
                suggest(f.apps.map { it.title })?.let { f.title = it } // Pixel auto-label (FolderIcon.setLabelSuggestion)
            }
            target is Folder && item is App -> target.apps += item
            else -> { pages[dragPage][dragCell] = item; state = State.NORMAL; dragItem = null; return true }
        }
        dragItem = null; state = State.NORMAL; current = p
        // Workspace.stripEmptyScreens: drop empty pages after the first
        val before = pages[current]; val first = pages[0]
        pages.removeAll { it.isEmpty() && it !== first }
        current = pages.indexOf(before).coerceAtLeast(0)
        events += "drop:${item.title}->${target?.title}"
        return true
    }

    private fun dropOnDock(i: Int): Boolean {
        val item = dragItem ?: return false
        val target = dock.getOrNull(i)
        pages[dragPage].remove(dragCell)
        if (target is App && item is App) dock[i] = Folder("", mutableListOf(target, item), false)
        else if (target == null) dock.add(item)
        dragItem = null; state = State.NORMAL
        events += "dock-drop:${item.title}->${target?.title}"
        return true
    }

    private fun openFolder(f: Folder): Boolean {
        folderPage = 0
        state = State.FOLDER; open = f; field = f.title; focused = false; editing = false; return true
    }

    private fun startEditing() {
        editing = true
        if (field.isEmpty()) suggest(open!!.apps.map { it.title })?.let { field = it } // Folder.showLabelSuggestions
    }

    private fun addToWorkspace(label: String): Boolean {
        fun free(p: Int) = (0 until COLS * ROWS).firstOrNull { pages[p][it] == null }
        val p = (listOf(current) + pages.indices).firstOrNull { free(it) != null }
            ?: run { pages += mutableMapOf(); pages.size - 1 }
        pages[p][free(p)!!] = App(label)
        state = State.NORMAL
        return true
    }
}
