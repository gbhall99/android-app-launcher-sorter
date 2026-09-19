package app.homesorter

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun setupScanPlanAndMoveWork() {
        rule.onNodeWithText("1 · Switch on access").assertExists()
        rule.onNodeWithText("Scan").assertExists()

        val inv = Inventory(listOf(HomeItem("Barclays", 0), HomeItem("Monzo", 0), HomeItem("Zyx", 0)), emptyList(), listOf("Phone"), 1)
        val apps = listOf(AppEntry("Barclays", "b", "Finance"), AppEntry("Monzo", "m", "Finance"), AppEntry("HSBC UK", "h", "Finance"))
        rule.runOnUiThread {
            Store.groups.clear(); Store.groups.addAll(Planner.build(inv, apps)); Store.inventory.value = inv
        }
        rule.onNodeWithText("3 loose icons · 1 page(s) · 0 folder(s) · dock (1) left alone").assertExists()
        val list = rule.onNode(hasScrollAction())
        list.performScrollToNode(hasText("HSBC UK"))
        rule.onNodeWithText("In app drawer · tick to add it here").assertExists()
        list.performScrollToNode(hasText("Zyx"))
        rule.onNodeWithText("Not sorted").assertExists()

        // Move Zyx from "Not sorted" into Finance via its menu.
        rule.onAllNodesWithText("Move")[3].performClick()
        rule.onNode(hasText("Finance") and hasClickAction() and !hasSetTextAction()).performClick() // the menu item, not the name field
        rule.waitForIdle()
        assertEquals(listOf("Barclays", "Monzo", "HSBC UK", "Zyx"), Store.groups.first().apps.map { it.label })
        assertEquals(true, Store.groups.first().apps.last().on)

        list.performScrollToNode(hasText("3 · Sort"))
        rule.onNodeWithText("Will create 1 folder(s), add to 0 existing folder(s), and bring 0 app(s) in from the drawer.").assertExists()
        list.performScrollToNode(hasText("+ New folder"))
        rule.onNodeWithText("+ New folder").performClick()
        rule.waitForIdle()
        assertEquals(listOf("Finance", "New folder", "Not sorted"), Store.groups.map { it.name })
    }
}
