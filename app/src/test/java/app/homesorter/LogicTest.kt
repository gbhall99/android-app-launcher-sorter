package app.homesorter

import org.junit.Assert.assertEquals
import org.junit.Test

class LogicTest {
    @Test fun launcherStringsBecomeMatchers() {
        assertEquals("Gmail", launcherPattern("Create folder with: %1\$s").matchEntire("Create folder with: Gmail")!!.groupValues[1])
        assertEquals("Social", launcherPattern("Folder: %1\$s, %2\$d items").matchEntire("Folder: Social, 3 items")!!.groupValues[1])
        assertEquals("Money (UK)", launcherPattern("Add to folder: %1\$s").matchEntire("Add to folder: Money (UK)")!!.groupValues[1])
    }

    @Test fun classifiesCommonApps() {
        val cases = mapOf(
            ("Barclays" to "com.barclays.android.barclaysmobilebanking") to "Finance",
            ("Starling" to "com.starlingbank.android") to "Finance",
            ("Lloyds" to "com.grppl.android.shell.CMBlloydsTSB73") to "Finance",
            ("Uber Eats" to "com.ubercab.eats") to "Food & Drink",
            ("Uber" to "com.ubercab") to "Travel",
            ("Outlook" to "com.microsoft.office.outlook") to "Work",
            ("Teams" to "com.microsoft.teams") to "Work",
            ("Messenger" to "com.facebook.orca") to "Messages",
            ("Facebook" to "com.facebook.katana") to "Social",
            ("Spotify" to "com.spotify.music") to "Watch & Listen",
            ("Prime Video" to "com.amazon.avod.thirdpartyclient") to "Watch & Listen",
            ("Amazon Shopping" to "com.amazon.mShop.android.shopping") to "Shopping",
            ("Octopus Energy" to "com.octopus.energy") to "Home",
            ("Home Assistant" to "io.homeassistant.companion.android") to "Home",
            ("Playtomic" to "com.playtomic.app") to "Health & Fitness",
            ("Claude" to "com.anthropic.claude") to "AI",
            ("Booking.com" to "com.booking") to "Travel",
            ("Wordle" to "com.nyt.wordle") to "Games",
            ("Airtable" to "com.formagrid.airtable") to "Work",
            ("Google Play Books" to "com.google.android.apps.books") to "News & Reading",
            ("Sainsbury's" to "com.sainsburys.gol") to "Food & Drink",
            ("Trainline" to "com.thetrainline") to "Travel",
            ("Chrome" to "com.android.chrome") to "Tools",
        )
        val wrong = cases.mapNotNull { (k, want) ->
            val got = Classifier.categorise(k.first, k.second, -1)
            if (got != want) "${k.first}: got $got, want $want" else null
        }
        assertEquals("", wrong.joinToString("\n"))
    }
}
