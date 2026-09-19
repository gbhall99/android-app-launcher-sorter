package app.homesorter

import android.content.pm.ApplicationInfo

/**
 * Three-tier categorisation: known package -> keywords in label/package -> the category the
 * developer declared (often blank). Anything left over lands in "Not sorted" for you to place.
 */
object Classifier {
    val order = listOf(
        "AI", "Messages", "Social", "Work", "Finance", "Shopping", "Food & Drink", "Travel",
        "Home", "Health & Fitness", "Watch & Listen", "Photos", "News & Reading", "Games", "Tools"
    )

    private val byPackage = mapOf(
        "com.google.android.gm" to "Messages", "com.google.android.apps.messaging" to "Messages",
        "com.google.android.dialer" to "Messages", "com.google.android.contacts" to "Messages",
        "com.whatsapp" to "Messages", "com.facebook.orca" to "Messages", "org.thoughtcrime.securesms" to "Messages",
        "com.anthropic.claude" to "AI", "com.openai.chatgpt" to "AI", "com.google.android.apps.bard" to "AI",
        "com.microsoft.copilot" to "AI", "ai.perplexity.app.android" to "AI",
        "com.linkedin.android" to "Work", "com.google.android.calendar" to "Work", "com.google.android.keep" to "Work",
        "com.google.android.apps.docs" to "Work", "com.google.android.apps.tachyon" to "Work",
        "com.amazon.mShop.android.shopping" to "Shopping", "com.amazon.avod.thirdpartyclient" to "Watch & Listen",
        "com.amazon.kindle" to "News & Reading", "com.amazon.dee.app" to "Home", "com.audible.application" to "Watch & Listen",
        "com.google.android.apps.chromecast.app" to "Home", "com.google.android.apps.maps" to "Travel",
        "com.google.android.apps.walletnfcrel" to "Finance", "com.google.android.apps.photos" to "Photos",
        "com.google.android.GoogleCamera" to "Photos", "com.google.android.youtube" to "Watch & Listen",
        "com.google.android.apps.youtube.music" to "Watch & Listen", "com.google.android.videos" to "Watch & Listen",
        "com.google.android.apps.fitness" to "Health & Fitness", "com.google.android.deskclock" to "Tools",
        "com.google.android.calculator" to "Tools", "com.google.android.apps.nbu.files" to "Tools",
        "com.android.vending" to "Tools", "com.android.settings" to "Tools", "com.google.android.apps.translate" to "Tools",
        "com.google.android.apps.recorder" to "Tools", "com.google.android.googlequicksearchbox" to "Tools",
        "com.google.android.apps.magazines" to "News & Reading", "com.google.android.apps.subscriptions.red" to "Tools",
        "com.azure.authenticator" to "Tools", "com.grppl.android.shell" to "Finance", "co.uk.getmondo" to "Finance",
        "com.rbs.mobile.android.natwest" to "Finance", "com.transferwise.android" to "Finance",
        "com.imaginecurve.curve.prd" to "Finance", "com.ubercab.eats" to "Food & Drink", "com.ubercab" to "Travel",
        "com.thetrainline" to "Travel", "com.ba.mobile" to "Travel", "net.skyscanner.android.main" to "Travel",
        "bbc.iplayer.android" to "Watch & Listen", "bbc.mobile.news.uk" to "News & Reading",
        "air.ITVMobilePlayer" to "Watch & Listen", "com.zhiliaoapp.musically" to "Social", "com.twitter.android" to "Social",
        "com.instagram.barcelona" to "Social", "io.homeassistant.companion.android" to "Home",
        "com.philips.lighting.hue2" to "Home", "com.app.tgtg" to "Food & Drink", "com.playtomic.app" to "Health & Fitness",
        "com.nhs.online.nhsonline" to "Health & Fitness", "com.etsy.android" to "Shopping",
    )

    // Order matters: the first category with a hit wins. Words of 5+ letters also match inside
    // longer tokens ("banking" in "barclaysmobilebanking"); shorter ones must match a whole token.
    private val keywords = listOf(
        "AI" to "claude chatgpt openai gemini perplexity copilot poe mistral grok deepseek ai",
        "Food & Drink" to "deliveroo eats justeat tesco clubcard sainsburys sainsbury nectar waitrose ocado asda morrisons lidl aldi coop mcdonalds starbucks costa greggs pret nandos dominos pizza recipe recipes food grocery groceries toogoodtogo hellofresh gousto wine coffee restaurant opentable",
        "Finance" to "bank banking pay paypal payments wallet money invest finance trading crypto coinbase revolut monzo starling barclays hsbc lloyds natwest nationwide santander halifax amex americanexpress chase wise curve vanguard freetrade nutmeg moneybox plum chip emma clearscore experian credit xero hmrc pension fnb capitec absa nedbank standardbank tax",
        "Messages" to "messag mail gmail email whatsapp telegram signal sms dialer phone contacts viber wechat skype",
        "Social" to "instagram facebook threads twitter tiktok snapchat reddit pinterest bereal mastodon bluesky discord nextdoor tumblr social",
        "Work" to "teams outlook slack zoom office word excel powerpoint onedrive sharepoint drive docs sheets slides keep calendar notion todoist trello asana jira confluence tableau workday concur webex meet tasks notes evernote obsidian airtable figma canva miro acrobat pdf dropbox box linkedin",
        "Shopping" to "amazon ebay vinted etsy argos johnlewis ikea asos shein temu aliexpress boots superdrug currys shopping shop depop screwfix toolstation wickes diy homebase primark voucher vouchers",
        "Travel" to "maps waze uber bolt citymapper trainline rail train trains tfl flight flights airline airlines airways easyjet ryanair booking airbnb expedia skyscanner tripadvisor hotels hotel trip travel ringgo parking parkopedia justpark zapmap electroverse podpoint chargepoint avios eurostar gwr nationalrail taxi ferry",
        "Home" to "home homeassistant nest hive hue tado ring octopus energy smartthings alexa roborock eufy switchbot tapo kasa meross ezviz arlo blink daikin melcloud thermostat boiler rightmove zoopla",
        "Health & Fitness" to "health fitness fit fitbit workout gym strava garmin nhs bupa axa doctor gp medical pharmacy sleep meditation headspace calm myfitnesspal padel playtomic tennis running physio rehab oura whoop peloton zwift yoga livi",
        "Watch & Listen" to "youtube spotify netflix primevideo disney iplayer sounds itvx itv channel4 all4 nowtv skygo plex kodi twitch audible podcast podcasts music radio tunein shazam soundcloud deezer tidal sonos tv video paramount crunchyroll britbox mubi vlc",
        "Photos" to "photo photos camera gallery lens snapseed lightroom vsco picsart gopro insta360 instax",
        "News & Reading" to "news guardian times telegraph economist ft kindle books book reader reading pocket medium substack flipboard magazine libby goodreads wikipedia newspaper sport sports",
        "Games" to "game games chess puzzle sudoku wordle crossword solitaire candy",
        "Tools" to "settings clock calculator calc files vending authenticator vpn password passwords 1password bitwarden lastpass weather accuweather translate recorder scanner qr keyboard torch flashlight compass backup security chrome firefox brave browser edge opera duckduckgo search tools utility utilities wallpaper wallpapers battery",
    ).map { (cat, words) -> cat to words.split(' ') }

    fun categorise(label: String, pkg: String?, declared: Int): String? {
        if (pkg != null) {
            byPackage.entries.filter { pkg == it.key || pkg.startsWith(it.key + ".") }
                .maxByOrNull { it.key.length }?.let { return it.value }
        }
        val tokens = (label.lowercase().split(Regex("[^a-z0-9]+")) +
            (pkg ?: "").lowercase().split('.', '_')).filter { it.isNotBlank() }
        for ((cat, words) in keywords) {
            if (words.any { w -> tokens.any { t -> t == w || (w.length >= 5 && t.contains(w)) } }) return cat
        }
        return when (declared) {
            ApplicationInfo.CATEGORY_GAME -> "Games"
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> "Watch & Listen"
            ApplicationInfo.CATEGORY_IMAGE -> "Photos"
            ApplicationInfo.CATEGORY_SOCIAL -> "Social"
            ApplicationInfo.CATEGORY_NEWS -> "News & Reading"
            ApplicationInfo.CATEGORY_MAPS -> "Travel"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Work"
            else -> null
        }
    }
}
