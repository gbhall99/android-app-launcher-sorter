package app.homesorter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** End-to-end runs of scan -> plan -> sort against the simulated launcher. */
class FlowTest {
    private val pkgs = mapOf(
        "Gmail" to "com.google.android.gm", "Barclays" to "com.barclays.android.barclaysmobilebanking",
        "Uber" to "com.ubercab", "Maps" to "com.google.android.apps.maps", "Spotify" to "com.spotify.music",
        "Monzo" to "co.uk.getmondo", "Netflix" to "com.netflix.mediaclient", "Trainline" to "com.thetrainline",
        "Revolut" to "com.revolut.revolut", "Zyx" to "com.example.zyx", "Slack" to "com.Slack",
        "Phone" to "com.google.android.dialer", "Chrome" to "com.android.chrome",
        "HSBC UK" to "uk.co.hsbc.hsbcukmobilebanking", "Teams" to "com.microsoft.teams", "Calculator" to "com.google.android.calculator",
    )
    private val installed = pkgs.map { (l, p) -> AppEntry(l, p, Classifier.categorise(l, p, -1)) }

    private fun homeScreen(strings: Map<String, String>? = FakeLauncher.AOSP, hotseatId: String? = "com.google.android.apps.nexuslauncher:id/hotseat",
                           suggest: (List<String>) -> String? = { "Suggested" }, clearFocusWorks: Boolean = true,
                           reportedHome: String? = FakeLauncher.REAL, homeButtonWorks: Boolean = true) = FakeLauncher(
        pageSetup = listOf(
            listOf("Gmail", "Barclays", "Uber", "Maps", "Spotify", "Monzo"),
            listOf("Netflix", null, "Trainline", "Revolut", "Zyx", "Work" to listOf("Slack")),
        ),
        dockApps = listOf("Phone", "Chrome"),
        drawer = pkgs.keys.toList(),
        strings = strings, hotseatId = hotseatId, suggest = suggest, dots = mapOf("Gmail" to 3),
        clearFocusWorks = clearFocusWorks, reportedHome = reportedHome, homeButtonWorks = homeButtonWorks,
    )

    private fun runAll(fake: FakeLauncher, tick: Set<String> = setOf("HSBC UK", "Teams"), say: (String) -> Unit = {}): Pair<List<Group>, Report> {
        val l = Launcher(fake, say)
        val inv = l.scan()
        assertEquals(listOf("Gmail", "Barclays", "Uber", "Maps", "Spotify", "Monzo", "Netflix", "Trainline", "Revolut", "Zyx"), inv.loose.map { it.label })
        assertEquals(listOf(HomeFolder("Work", listOf("Slack"))), inv.folders)
        assertEquals(listOf("Phone", "Chrome"), inv.dock)
        assertEquals(2, inv.pages)
        assertEquals("scan must leave the launcher on the first page", 0, fake.current)
        val groups = Planner.build(inv, installed)
        groups.flatMap { it.apps }.filter { it.label in tick }.forEach { it.on = true }
        return groups to Executor.run(l, Planner.jobs(groups))
    }

    private fun assertSorted(fake: FakeLauncher, report: Report) {
        assertEquals("problems: ${report.problems}", emptyList<String>(), report.problems)
        val f = fake.folders()
        assertEquals(setOf("Barclays", "Monzo", "Revolut", "HSBC UK"), f["Finance"]?.toSet())
        assertEquals(setOf("Uber", "Maps", "Trainline"), f["Travel"]?.toSet())
        assertEquals(setOf("Spotify", "Netflix"), f["Watch & Listen"]?.toSet())
        assertEquals(setOf("Slack", "Teams"), f["Work"]?.toSet())
        assertEquals(setOf("Finance", "Travel", "Watch & Listen", "Work"), f.keys)
        assertEquals(setOf("Gmail", "Zyx"), fake.loose().toSet())
        assertEquals(listOf("Phone", "Chrome"), fake.dock.map { it.title })
        assertEquals(FakeLauncher.State.NORMAL, fake.state)
    }

    @Test fun planMatchesExpectations() {
        val (groups, _) = runAll(homeScreen())
        val byName = groups.associateBy { it.display() }
        assertEquals(setOf("Barclays", "Monzo", "Revolut", "HSBC UK"), byName["Finance"]!!.apps.map { it.label }.toSet())
        assertTrue("existing folder listed first", groups.first().existing)
        assertEquals(listOf("Zyx"), groups.last().apps.map { it.label })
        assertTrue("single-app Messages group is not executed", Planner.jobs(groups).none { it.name == "Messages" })
    }

    @Test fun sortsWithPixelStringsAndAutoNames() { val f = homeScreen(); val (_, r) = runAll(f); assertSorted(f, r) }

    @Test fun sortsWhenLauncherStringsAreHidden() {
        val f = homeScreen(strings = null, hotseatId = null); val (_, r) = runAll(f); assertSorted(f, r)
    }

    @Test fun sortsWhenLauncherDoesNotAutoName() {
        val f = homeScreen(suggest = { null }); val (_, r) = runAll(f); assertSorted(f, r)
    }

    @Test fun missingDrawerAppIsReportedAndRestContinues() {
        val f = homeScreen()
        val l = Launcher(f) {}
        val inv = l.scan()
        val groups = Planner.build(inv, installed + AppEntry("Ghost Bank", "com.ghost.bank", "Finance"))
        groups.flatMap { it.apps }.filter { it.label == "Ghost Bank" }.forEach { it.on = true }
        val r = Executor.run(l, Planner.jobs(groups))
        assertEquals(1, r.problems.size)
        assertTrue(r.problems[0].startsWith("Ghost Bank"))
        assertEquals(setOf("Barclays", "Monzo", "Revolut"), f.folders()["Finance"]?.toSet())
    }

    @Test fun diagnoseLeavesHomeScreenUntouched() {
        val f = homeScreen()
        val before = f.loose() to f.folders()
        val out = Launcher(f) {}.diagnose()
        assertTrue(out.contains("While moving “Gmail”"))
        assertTrue(out.contains("Create folder with: Barclays"))
        assertEquals(before, f.loose() to f.folders())
        assertEquals(FakeLauncher.State.NORMAL, f.state)
    }

    @Test fun stopHaltsPromptly() {
        val f = homeScreen()
        lateinit var l: Launcher
        var n = 0
        l = Launcher(f) { if (++n == 3) l.stopped = true }
        try { l.scan(); fail("should have stopped") } catch (e: StopException) { /* expected */ }
    }

    @Test fun namesStickWhenOnlyBackCommits() {
        val f = homeScreen(clearFocusWorks = false); val (_, r) = runAll(f); assertSorted(f, r)
    }

    @Test fun neverTouchesSmartspaceOrDrawerSearch() {
        val f = homeScreen(); runAll(f)
        assertTrue(f.events.none { it == "smartspace-scrolled" || it == "drawer-search-touched" || it.startsWith("dock-drop") })
    }

    // Android 11+ package visibility: until the manifest declared a HOME query, the system named Settings'
    // fallback home (or nothing) as the home app, so the real launcher never "came to the front".
    @Test fun adoptsTheRealHomeAppWhenTheSystemNamesTheWrongOne() {
        val f = homeScreen(reportedHome = "com.android.settings")
        val said = mutableListOf<String>()
        val (_, r) = runAll(f, say = { said += it })
        assertSorted(f, r)
        assertEquals(FakeLauncher.REAL, f.launcherPackage)
        assertTrue(said.toString(), said.any { it == "Home app is ${FakeLauncher.REAL}, not com.android.settings as the system said" })
    }

    @Test fun adoptsTheHomeAppWhenTheSystemWontNameIt() {
        val f = homeScreen(reportedHome = null)
        val said = mutableListOf<String>()
        val (_, r) = runAll(f, say = { said += it })
        assertSorted(f, r)
        assertEquals(FakeLauncher.REAL, f.launcherPackage)
        assertTrue(said.toString(), said.any { it == "Home app: ${FakeLauncher.REAL}" })
    }

    @Test fun failsNamingTheWindowsWhenNothingComesToTheFront() {
        val f = homeScreen(homeButtonWorks = false)
        try { Launcher(f) {}.scan(); fail("should have failed") } catch (e: DriveException) {
            val m = e.message.orEmpty()
            assertTrue(m, m.contains("didn't come to the front") && m.contains("expected ${FakeLauncher.REAL}") && m.contains("[app app.homesorter active]"))
        }
    }

    @Test fun diagnoseStillProducesAReportWhenHomeFails() {
        val out = Launcher(homeScreen(homeButtonWorks = false)) {}.diagnose()
        assertTrue(out, out.contains("Windows before pressing Home: [app app.homesorter active]") && out.contains("FAILED: the launcher didn't come to the front"))
    }

    @Test fun renameCommitsEvenWithSuggestionRace() {
        // Suggestion fills the empty field on focus; our typed name must still win.
        val f = homeScreen(suggest = { "Money" })
        val (_, r) = runAll(f)
        assertSorted(f, r)
    }
}

class EdgeTest {
    @Test fun folderSkippedWhenDrawerFailureLeavesOneApp() {
        val f = FakeLauncher(listOf(listOf("Barclays", "Uber")), emptyList(), listOf("Barclays", "Uber"))
        val l = Launcher(f) {}
        val r = Executor.run(l, listOf(FolderJob("Finance", false, listOf("Barclays", "Ghost"), listOf("Ghost"))))
        assertEquals(2, r.problems.size)
        assertTrue(r.problems[1].contains("fewer than 2"))
        assertEquals(emptyMap<String, List<String>>(), f.folders())
    }

    @Test fun addsToExistingUnnamedAwareFolderAcrossThreePages() {
        val f = FakeLauncher(
            listOf(listOf("Travel" to listOf("Maps")), listOf("Uber"), listOf("Trainline")),
            emptyList(), listOf("Maps", "Uber", "Trainline"))
        val l = Launcher(f) {}
        val r = Executor.run(l, listOf(FolderJob("Travel", true, listOf("Uber", "Trainline"), emptyList())))
        assertEquals(emptyList<String>(), r.problems)
        assertEquals(setOf("Maps", "Uber", "Trainline"), f.folders()["Travel"]?.toSet())
        assertEquals("emptied pages are removed", 1, f.pages.size)
    }

    @Test fun drawerAppPlacedOnNewPageWhenHomeIsFull() {
        val full = (1..25).map { "App$it" }
        val f = FakeLauncher(listOf(full), emptyList(), full + listOf("HSBC UK", "Barclays2"))
        val l = Launcher(f) {}
        val r = Executor.run(l, listOf(FolderJob("Finance", false, listOf("HSBC UK", "Barclays2"), listOf("HSBC UK", "Barclays2"))))
        assertEquals(emptyList<String>(), r.problems)
        assertEquals(setOf("HSBC UK", "Barclays2"), f.folders()["Finance"]?.toSet())
    }
}

class SafetyTest {
    private fun twin(hotseatId: String?) = FakeLauncher(
        listOf(listOf("Outlook", null, null, "Gmail")), dockApps = listOf("Gmail", "Phone"),
        drawer = listOf("Gmail", "Outlook", "Phone"), hotseatId = hotseatId)

    @Test fun dockTwinIsNeverUsedAsDropTarget() {
        for (id in listOf("com.google.android.apps.nexuslauncher:id/hotseat", null)) {
            val f = twin(id)
            Launcher(f) {}.createFolder("Gmail", "Outlook", "Mail")
            assertEquals("hotseat id=$id", setOf("Gmail", "Outlook"), f.folders()["Mail"]?.toSet())
            assertEquals(listOf("Gmail", "Phone"), f.dock.map { it.title })
            assertTrue(f.dock.all { it is FakeLauncher.App })
        }
    }

    @Test fun dragFallbackWorksOnSamePage() {
        val f = FakeLauncher(listOf(listOf("Barclays", "Monzo", "Revolut")), emptyList(), listOf("Barclays", "Monzo", "Revolut"), moveSupported = false)
        val r = Executor.run(Launcher(f) {}, listOf(FolderJob("Finance", false, listOf("Barclays", "Monzo", "Revolut"), emptyList())))
        assertEquals(emptyList<String>(), r.problems)
        assertEquals(setOf("Barclays", "Monzo", "Revolut"), f.folders()["Finance"]?.toSet())
    }

    @Test fun dragFallbackAcrossPagesFailsSafely() {
        val f = FakeLauncher(listOf(listOf("Barclays"), listOf("Monzo")), emptyList(), listOf("Barclays", "Monzo"), moveSupported = false)
        val r = Executor.run(Launcher(f) {}, listOf(FolderJob("Finance", false, listOf("Barclays", "Monzo"), emptyList())))
        assertEquals(1, r.problems.size)
        assertEquals(emptyMap<String, List<String>>(), f.folders())
        assertEquals(setOf("Barclays", "Monzo"), f.loose().toSet())
    }

    @Test fun scanReadsEveryPageOfABigFolder() {
        val many = (1..20).map { "App%02d".format(it) }
        val f = FakeLauncher(listOf(listOf("Big" to many, "Solo")), emptyList(), many + "Solo")
        val inv = Launcher(f) {}.scan()
        assertEquals(many, inv.folders.single().apps)
        assertEquals(listOf("Solo"), inv.loose.map { it.label })
        assertEquals(FakeLauncher.State.NORMAL, f.state)
    }

    @Test fun unnamedExistingFolderIsNeverATarget() {
        val inv = Inventory(listOf(HomeItem("Barclays", 0)), listOf(HomeFolder("", listOf("Monzo"))), emptyList(), 1)
        val groups = Planner.build(inv, listOf(AppEntry("Barclays", "b", "Finance"), AppEntry("Monzo", "m", "Finance")))
        assertTrue(groups.none { it.existing })
        assertTrue("Monzo is already on the home screen, so not suggested", groups.flatMap { it.apps }.none { it.label == "Monzo" })
    }

    @Test fun nothingIsLostAcrossAFullRun() {
        val f = FakeLauncher(
            listOf(listOf("Barclays", "Uber", "Zyx", "Maps"), listOf("Monzo", "Spotify"), listOf("Netflix", "Revolut")),
            listOf("Phone"), listOf("Barclays", "Uber", "Zyx", "Maps", "Monzo", "Spotify", "Netflix", "Revolut", "Phone"))
        val before = (f.loose() + f.dock.map { it.title }).toSet()
        val l = Launcher(f) {}
        val apps = before.map { AppEntry(it, "x", Classifier.categorise(it, null, -1)) }
        val r = Executor.run(l, Planner.jobs(Planner.build(l.scan(), apps)))
        assertEquals(emptyList<String>(), r.problems)
        val after = (f.loose() + f.folders().values.flatten() + f.dock.map { it.title }).toSet()
        assertEquals(before, after)
        assertEquals(3, f.folders().size)
    }
}
