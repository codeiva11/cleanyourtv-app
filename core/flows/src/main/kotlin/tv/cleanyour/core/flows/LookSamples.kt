package tv.cleanyour.core.flows

import java.time.LocalDate
import java.time.ZoneId
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.spec.AppNames
import tv.cleanyour.core.spec.BandChoice
import tv.cleanyour.core.spec.ButtonsChoice
import tv.cleanyour.core.spec.FactsTable
import tv.cleanyour.core.spec.GlowChoice
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.Movement
import tv.cleanyour.core.spec.PackageFacts
import tv.cleanyour.core.spec.PictureSource
import tv.cleanyour.core.spec.StyleId
import tv.cleanyour.core.spec.ThemeSource
import tv.cleanyour.core.store.Clock

// One case of the look2 emulator matrix (build plan section 10): a saved choice, the dock it is
// pushed with and the TV.
data class LookSample(val name: String, val choice: HomeChoice, val dock: List<PackageName>, val tv: LookTv = LookTv(8L, 2048, false))

// Every case of the emulator matrix by its name, with the bytes a push of it sends and the phone's
// preview of them, for the byte dump (LookBytesDumpTest) and the side by side goldens. Used by tests
// only, like LookDocs. Unless a case says otherwise: the Mist picture, 5 apps in a row, a 4 app dock
// and Lively movement. `legacy-<id>` is a saved 0.4.0 look (its 0.4.0 bytes); `next-<id>` is the
// first send of its untouched migrated draft.
object LookSamples {
    val dock4: List<PackageName> = listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar")
        .map(::PackageName)

    // The emulator's stand-in apps (LookBytesDumpTest).
    val dock15: List<PackageName> = dock4 + listOf(
        "com.spotify.tv.android", "com.plexapp.android", "com.sonyliv", "com.graymatrix.did", "com.jio.media.ondemand",
        "com.example.tvbrowser", "org.videolan.vlc", "tv.twitch.android.app", "com.disney.disneyplus",
        "com.apple.atve.androidtv.appletv", "com.zattoo.player",
    ).map(::PackageName)

    val LEGACY_IDS = listOf("default", "aurora", "six-columns", "cinema", "console", "horizon", "quiet", "diwali", "library", "arcade", "mist")

    private fun look(style: StyleId, picture: String = "mist", columns: Int = 5, movement: Movement = Movement.LIVELY, lift: Boolean = false,
        names: Boolean = false, details: LookDetails = LookDetails(), dock: List<PackageName> = dock4): HomeChoice =
        HomeChoice(dock, null, cards = false, picture = picture, columns = columns, style = style.id, movement = movement.id, lift = lift,
            names = names, details = details)

    private fun case(name: String, choice: HomeChoice) = LookSample(name, choice, choice.dock)

    // The first send of an untouched migrated 0.4.0 look on a 2 GB TV (no auto pick): the new form
    // of its migration table row, with its saved apps in a row.
    fun next(legacy: HomeChoice): HomeChoice {
        val id = legacy.themeId ?: DEFAULT_LOOK
        val e = LegacyLooks.of(id)
        return HomeChoice(legacy.dock, null, cards = legacy.cards, picture = legacy.picture ?: e.picture, dim = legacy.dim,
            columns = legacy.columns ?: e.columns, style = e.style.id, movement = e.movement.id, lift = e.lift, names = false, details = e.details)
    }

    val matrix: List<LookSample> by lazy {
        val styles = StyleId.entries.flatMap { s -> listOf(case("style-${s.id}-mist", look(s)), case("style-${s.id}-none", look(s, LookPicture.NONE_ID))) }
        val c = StyleId.CLASSIC
        val details = listOf(
            case("d-bigname-off", look(c, details = LookDetails(bigName = false))),
            case("d-glow-top", look(c, details = LookDetails(glow = GlowChoice.FROM_TOP))),
            case("d-glow-none", look(c, details = LookDetails(glow = GlowChoice.NONE))),
            case("d-ring-on", look(c, details = LookDetails(ring = true))),
            case("d-band-see50", look(c, details = LookDetails(band = BandChoice.SEE_THROUGH))),
            case("d-band-glass", look(c, details = LookDetails(band = BandChoice.GLASS))),
            case("d-buttons-round", look(c, details = LookDetails(buttons = ButtonsChoice.ROUND))),
            case("d-buttons-menu", look(c, details = LookDetails(buttons = ButtonsChoice.MENU))),
            case("d-wifi-green", look(c, details = LookDetails(greenWifi = true))),
            case("d-dim-40", look(c, details = LookDetails(dim = 40))),
            case("d-sky-on", look(c, details = LookDetails(sky = true))),
            case("d-spot-colour-off", look(StyleId.SPOTLIGHT, details = LookDetails(spotColour = false))),
            case("d-spot-ring-off", look(StyleId.SPOTLIGHT, details = LookDetails(ring = false))),
            case("adv-see70", look(c, details = LookDetails(band = BandChoice.SEE_THROUGH, seeThrough = 70))),
            case("adv-see30", look(c, details = LookDetails(band = BandChoice.SEE_THROUGH, seeThrough = 30))),
            case("adv-skystr-100", look(c, "mountains", details = LookDetails(skyStrength = 100))),
            case("adv-lift-on", look(c, lift = true)),
            case("names-classic", look(c, names = true)),
            case("names-spotlight", look(StyleId.SPOTLIGHT, names = true)),
            case("names-allapps", look(StyleId.ALL_APPS, names = true)),
            case("movement-off-diwali", look(c, "diwali", movement = Movement.OFF)),
            case("movement-quick-mist", look(c, movement = Movement.QUICK)),
        )
        val columns = listOf(
            case("cols-4-dock4", look(c, columns = 4)),
            case("cols-4-dock15", look(c, columns = 4, dock = dock15)),
            case("cols-8-dock4", look(c, columns = 8)),
            case("cols-8-dock15", look(c, columns = 8, dock = dock15)),
            case("cols-8-allapps", look(StyleId.ALL_APPS, columns = 8)),
            case("cols-4-spotlight-dock15", look(StyleId.SPOTLIGHT, columns = 4, dock = dock15)),
        )
        val legacy = LEGACY_IDS.map { id -> "legacy-$id" to HomeChoice(dock4, id, cards = false) } +
            ("legacy-console-cols5" to HomeChoice(dock4, "console", cards = false, columns = 5))
        val migrated = legacy.map { (n, ch) -> case(n, ch) } + legacy.map { (n, ch) -> case("next-" + n.removePrefix("legacy-"), next(ch)) }
        styles + details + columns + migrated
    }

    // The doc, its bytes and the wallpaper the push sends for a sample, exactly as a push makes them
    // (a legacy choice through the 0.4.0 path).
    fun push(sample: LookSample, themes: ThemeSource, pictures: PictureSource): Triple<LauncherConfigDoc, ByteArray, BlobRef?> {
        val drafts = LookDrafts(themes, pictures, ConfigEncoder.Codec, DAY)
        val (doc, wallpaper) = drafts.docOf(sample.choice, sample.dock, sample.choice.cards ?: false, null, sample.tv)
        return Triple(doc, ConfigEncoder.Codec.encode(doc), wallpaper)
    }

    // The phone's preview of those bytes, for the side by side goldens (names from AppNames on a
    // null facts table).
    fun preview(sample: LookSample, themes: ThemeSource, pictures: PictureSource, today: LocalDate, minuteOfDay: Int): LookPreview {
        val (_, bytes, wallpaper) = push(sample, themes, pictures)
        val apps = sample.dock.map { AppChoice(it, AppNames.of(it, NO_FACTS)) }
        return LookPreview.of(bytes, wallpaper, apps, emptyList(), today, minuteOfDay, sample.tv.hasInputs)
    }

    private val NO_FACTS = object : FactsTable { override fun lookup(pkg: PackageName): PackageFacts? = null }

    // 2026-10-26, the day the matrix is checked on (Diwali is offered).
    private val DAY = object : Clock {
        override fun nowMs(): Long = LocalDate.of(2026, 10, 26).atTime(20, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        override fun monotonicMs(): Long = 0
    }
}
