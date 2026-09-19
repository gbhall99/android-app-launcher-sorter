package app.homesorter

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process

data class AppEntry(val label: String, val pkg: String, val category: String?)
data class FolderJob(val name: String, val existing: Boolean, val apps: List<String>, val fromDrawer: List<String>)

object Apps {
    /** Uses the same labels the launcher shows, so they match the icons on screen. */
    fun installed(ctx: Context): List<AppEntry> =
        ctx.getSystemService(LauncherApps::class.java).getActivityList(null, Process.myUserHandle())
            .filter { it.componentName.packageName != ctx.packageName }
            .map { a ->
                val label = a.label.toString()
                val pkg = a.componentName.packageName
                AppEntry(label, pkg, Classifier.categorise(label, pkg, a.applicationInfo.category))
            }
            .distinctBy { it.label.lowercase() }
}

object Planner {
    fun build(inv: Inventory, apps: List<AppEntry>): List<Group> {
        val byLabel = apps.associateBy { it.label.lowercase() }
        fun cat(label: String) = byLabel[label.lowercase()]?.category ?: Classifier.categorise(label, null, -1)

        val groups = LinkedHashMap<String, Group>()
        inv.folders.filter { it.name.isNotBlank() }.forEach { groups[it.name.lowercase()] = Group(it.name, existing = true) }
        val unsorted = Group("Not sorted", existing = false, unsorted = true)

        for (item in inv.loose.distinctBy { it.label.lowercase() }) {
            val c = cat(item.label)
            if (c == null) unsorted.apps += PlanApp(item.label, fromDrawer = false, on = false)
            else groups.getOrPut(c.lowercase()) { Group(c, existing = false) }.apps += PlanApp(item.label, false, true)
        }

        // Suggest drawer apps that fit a folder you'll have anyway. Unticked until you choose.
        val onHome = (inv.loose.map { it.label } + inv.folders.flatMap { it.apps } + inv.dock).map { it.lowercase() }.toSet()
        for (a in apps.sortedBy { it.label.lowercase() }) {
            if (a.label.lowercase() in onHome || a.category == null) continue
            groups[a.category.lowercase()]?.apps?.add(PlanApp(a.label, fromDrawer = true, on = false))
        }

        val rank = { g: Group -> if (g.existing) -1 else Classifier.order.indexOf(g.name).let { if (it < 0) 99 else it } }
        return groups.values
            .filter { g -> g.apps.any { !it.fromDrawer } || (g.existing && g.apps.isNotEmpty()) }
            .sortedBy(rank) + unsorted
    }

    fun jobs(groups: List<Group>): List<FolderJob> = groups.filter { !it.unsorted }.mapNotNull { g ->
        val picked = g.apps.filter { it.on }
        val name = g.name.trim()
        val viable = name.isNotEmpty() && if (g.existing) picked.isNotEmpty() else picked.size >= 2
        if (!viable) null
        else FolderJob(name, g.existing, picked.map { it.label }, picked.filter { it.fromDrawer }.map { it.label })
    }
}

data class Report(val created: Int, val filed: Int, val problems: List<String>)

object Executor {
    /** Runs the plan. One failed app is reported and skipped; it never aborts the rest. */
    fun run(l: Launcher, jobs: List<FolderJob>): Report {
        val problems = mutableListOf<String>()
        val gone = HashSet<String>()
        var created = 0
        var filed = 0
        for (app in jobs.flatMap { it.fromDrawer }) {
            try { l.addFromDrawer(app) } catch (e: DriveException) { problems += "$app: ${e.message}"; gone += app.lowercase() }
        }
        for (job in jobs) {
            val queue = job.apps.filter { it.lowercase() !in gone }.toMutableList()
            if (!job.existing) {
                var made = false
                while (!made && queue.size >= 2) {
                    try {
                        l.createFolder(queue[0], queue[1], job.name)
                        made = true; created++
                        queue.subList(0, 2).clear()
                    } catch (e: DriveException) {
                        // If one of the pair has gone missing, carry on with the next app instead.
                        val missing = queue.take(2).firstOrNull { it.equals(e.missing, true) }
                        if (missing == null) { problems += "“${job.name}”: ${e.message}"; break }
                        problems += "$missing: ${e.message}"
                        queue.remove(missing)
                    }
                }
                if (!made) {
                    if (queue.size < 2 && problems.none { it.startsWith("“${job.name}”") }) {
                        problems += "“${job.name}”: skipped, fewer than 2 of its apps could be found"
                    }
                    continue
                }
            }
            for (app in queue) {
                try { l.addToFolder(app, job.name); filed++ } catch (e: DriveException) { problems += "$app: ${e.message}" }
            }
        }
        l.goHome()
        val tail = if (problems.isEmpty()) "" else " · ${problems.size} problem(s), details below"
        l.note("Done · $created folder(s) made, $filed more app(s) filed$tail")
        return Report(created, filed, problems)
    }
}
