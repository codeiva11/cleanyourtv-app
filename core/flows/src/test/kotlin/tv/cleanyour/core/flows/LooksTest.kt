package tv.cleanyour.core.flows

import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.spec.BundledThemeSource
import tv.cleanyour.core.spec.DecodeResult
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.Picture
import tv.cleanyour.core.spec.Theme
import tv.cleanyour.testing.fixtures.FakeThemeSource
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.testing.fixtures.sha256Of
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 0.4.0 looks: a dated look (Diwali, until 2026-11-10) keeps the look the person had before it, is
// offered up to its last day, and afterwards the phone counts the earlier look as the TV's.
class LooksTest {
    private val lastDay = LocalDate.of(2026, 11, 10)
    private val dock = listOf(PackageName("com.netflix.ninja"), PackageName("com.google.android.youtube.tv"))

    @Test fun aDatedLookKeepsTheLookBeforeIt() {
        val cinema = HomeChoice(dock, "cinema")
        val diwali = cinema.withTheme("diwali")
        assertEquals("cinema", diwali.previousThemeId)
        assertEquals("cinema", diwali.withTheme("diwali").previousThemeId, "picked again: still the one before")
        assertNull(diwali.withTheme("console").previousThemeId, "an undated look forgets it")
        assertNull(HomeChoice(dock, null).withTheme("diwali").previousThemeId, "Default before it")
        assertEquals("diwali", diwali.lookOn(lastDay))
        assertNull(diwali.endedLook(lastDay))
        assertEquals("cinema", diwali.lookOn(lastDay.plusDays(1)))
        assertEquals("diwali", diwali.endedLook(lastDay.plusDays(1)))
        assertEquals("cinema", cinema.lookOn(lastDay.plusYears(1)))
    }
    @Test fun homeTxtKeepsTheEarlierLookAndOldFilesStillLoad() = runTest {
        val h = Harness()
        val prefs = HomePrefs(h.documents)
        val id = h.knownTv().id
        val choice = HomeChoice(dock, "diwali", cards = false, previousThemeId = "horizon")
        prefs.save(id, choice)
        assertEquals(choice, prefs.load(id))
        h.documents.write("tvs/${id.value}/home.txt", "dock=com.netflix.ninja\ntheme=aurora\n".encodeToByteArray())
        assertEquals(HomeChoice(dock.take(1), "aurora"), prefs.load(id))
    }

    private val glass = Picture("glass", "Glass", BlobRef(Samples.zeroHash, 1000, "/blobs/glass.jpg"))
    private val ownPicture = BlobRef(sha256Of("cinema".encodeToByteArray()), 2000, "/blobs/cinema.jpg")
    private val gallery = FakeThemeSource(listOf(
        Theme("quiet", "Quiet black", "Black", LauncherConfigDoc(wallpaper = false, wallpaperDim = 0, wallpaperRotationMinutes = 0, reducedMotion = true), null),
        Theme("cinema", "Cinema", "Curtains", LauncherConfigDoc(wallpaper = true, wallpaperDim = 0, wallpaperRotationMinutes = 0, cinematicHome = true), ownPicture),
        Theme("diwali", "Diwali", "Lamps", LauncherConfigDoc(wallpaper = true, festival = "diwali", festivalUntil = "2026-11-10"), ownPicture),
    ), listOf(glass))

    // A picture over a look that shows none (Quiet black) forces wallpaper on, or it would be
    // pushed and not shown; "No picture" turns it off and sends no wallpaper.jpg; a dated look
    // keeps its own whatever was chosen.
    @Test fun theEncoderForcesWallpaperOnWhenAPictureIsChosen() {
        val quiet = gallery.bundled()[0]
        val (doc, wallpaper) = Look.docOf(quiet, dock, cards = false, LookPicture.of("glass", gallery))
        assertEquals(true, doc.wallpaper)
        assertEquals(0, doc.wallpaperRotationMinutes)
        assertEquals(glass.wallpaper, wallpaper)
        assertEquals(true, doc.reducedMotion, "the rest of the look stays")
        val cinema = gallery.bundled()[1]
        val (none, noFile) = Look.docOf(cinema, dock, cards = false, LookPicture.of(LookPicture.NONE_ID, gallery))
        assertEquals(false, none.wallpaper)
        assertNull(noFile)
        assertEquals(cinema.doc.copy(dockApps = dock, rowsBelowDock = 0, moduleCards = false, fillDock = false) to ownPicture,
            Look.docOf(cinema, dock, cards = false, LookPicture.of(null, gallery)), "the look's own")
        assertEquals(ownPicture, Look.docOf(cinema, dock, cards = false, LookPicture.of("gone", gallery)).second, "a picture no longer there: the look's own")
        val (festive, festiveFile) = Look.docOf(gallery.bundled()[2], dock, cards = false, LookPicture.of("glass", gallery))
        assertEquals(true, festive.wallpaper)
        assertEquals(ownPicture, festiveFile)
    }

    @Test fun homeTxtKeepsThePicture() = runTest {
        val h = Harness()
        val id = h.knownTv().id
        val choice = HomeChoice(dock, "quiet", cards = true, picture = "glass")
        HomePrefs(h.documents).save(id, choice)
        assertEquals(choice, HomePrefs(h.documents).load(id))
    }

    // The preview's keys are the ones the TV reads from the pushed bytes, for every bundled look.
    @Test fun thePreviewIsReadBackFromTheBytesThePushSends() {
        val today = LocalDate.of(2026, 10, 25)
        val names = listOf("Netflix", "YouTube")
        for (t in BundledThemeSource().bundled()) {
            val (doc, wallpaper) = Look.docOf(t, dock, cards = true, null)
            val bytes = LauncherConfigCodec.encode(doc)
            val p = LookPreview.of(bytes, wallpaper?.copy(path = "/files/${t.id}.jpg"), names, today, 600)
            val e = LauncherConfigCodec.effective(assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(bytes)).doc, LauncherConfigCodec.builtInDefaults)
            assertEquals(listOf(e.backgroundColorArgb, e.wallpaperDim, e.skyIntensity, e.columns, e.rowsBelowDock),
                listOf(p.backgroundArgb, p.dim, p.sky, p.columns, p.rowsBelowDock), t.id)
            assertEquals(listOf(e.dockRow, e.dockTitle, e.cinematicHome), listOf(p.dockRow, p.dockTitle, p.cinematic), t.id)
            assertEquals(if (e.wallpaper && wallpaper != null) "/files/${t.id}.jpg" else null, p.picturePath, t.id)
            assertEquals(names, p.dock)
        }
        val diwali = BundledThemeSource().bundled().first { it.id == "diwali" }
        val bytes = LauncherConfigCodec.encode(Look.docOf(diwali, dock, cards = true, null).first)
        val on = LookPreview.of(bytes, null, names, today, 600)
        assertEquals(true, on.festive); assertEquals(0xFFFFB23C.toInt(), on.accentArgb); assertEquals("Happy Diwali", on.greeting)
        assertEquals(LookMotion.CRISP, on.motion)
        val after = LookPreview.of(bytes, null, names, lastDay.plusDays(1), 600)
        assertEquals(false, after.festive); assertEquals("", after.greeting)
    }

    // 0.4.0 pre-release fixes, as the bytes reach the TV. Quiet black and Horizon turn the launcher's
    // glow in the focused app's colour off (ambient_glow, a launcher 2.7 key carried as is), so Quiet
    // is truly black and Horizon's sky is the clock's, not Netflix red. Cinema also turns its shelf's
    // scrim and art down so its curtains show, and keeps rows_below_dock 0 with a long dock so the
    // big name stays clear of the clock, with every app still in dock_apps. The other looks leave
    // the glow as the launcher has it.
    @Test fun theFixedLooksSendWhatTheTvNeeds() {
        val looks = BundledThemeSource().bundled().associateBy { it.id }
        val eight = (1..8).map { PackageName("com.example.app$it") }
        fun sent(id: String, apps: List<PackageName> = dock) =
            LauncherConfigCodec.encode(Look.docOf(looks.getValue(id), apps, cards = false, null).first).decodeToString()
        for (id in listOf("quiet", "horizon", "cinema")) assertTrue("\"ambient_glow\": false" in sent(id), id)
        for (id in listOf("default", "aurora", "six-columns", "console", "diwali", "library", "arcade", "mist")) {
            assertTrue("ambient_glow" !in sent(id), id)
        }
        val cinema = sent("cinema", eight)
        assertTrue("\"cinematic_scrim\": 45" in cinema && "\"cinematic_art\": 20" in cinema, cinema)
        val back = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(cinema.encodeToByteArray()))
        assertEquals(emptyList(), back.warnings)
        assertEquals(0, back.doc.rowsBelowDock)
        assertEquals(eight, back.doc.dockApps, "no app is lost")
        assertEquals(1, assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(sent("mist", eight).encodeToByteArray())).doc.rowsBelowDock,
            "a look without the cinematic shelf still shows the row")
    }

    // "Your photo": the photo the phone made is staged and chosen in the sheet; "Put it on the TV" sends
    // it as wallpaper.jpg with its dim (wallpaper forced on, no rotation) and only then keeps it for
    // this TV in its private files. After the cache lost it, "Restore mine" still sends it, from the
    // private copy.
    // A photo picked and then cancelled, or one the TV never got, changes nothing: the kept copy,
    // its dim and "Restore mine" stay the photo on the TV, and the sheet's dim goes back to it.
    // look2 pictures (spec 6.2): every picture of today's looks, with its background colour, and no
    // new image bytes; the 0.4.0 ids keep working.
    @Test fun theGalleryHasEveryPictureWithItsColour() {
        val p = BundledThemeSource().pictures()
        assertEquals(listOf("diwali", "aurora", "mist", "glass", "ruska", "curtains", "mountains", "lamp", "neon", "cobalt"), p.map { it.id })
        assertEquals(listOf("Diwali", "Aurora", "Mist", "Navy", "Autumn lake", "Curtains", "Mountains", "Lamp light", "Neon", "Cobalt"), p.map { it.name })
        assertEquals(listOf(0x120809, 0x070A14, 0x0E1022, 0x090C14, 0x07080D, 0x07060A, 0x000000, 0x141210, 0x0B0614, 0x020611),
            p.map { it.backgroundArgb and 0xFFFFFF })
        assertTrue(p.all { it.backgroundArgb ushr 24 == 0xFF })
        val diwali = p.first()
        assertEquals(listOf<Any?>(lastDay, "diwali", "#FFB23C"), listOf(diwali.until, diwali.festival, diwali.accentColor))
        assertEquals(listOf(55), p.filter { it.sky > 0 }.map { it.sky })
        assertEquals("mountains", p.single { it.sky > 0 }.id)
        assertTrue(p.drop(1).all { it.until == null && it.festival == null })
        val themes = BundledThemeSource().bundled().associateBy { it.id }
        for ((picture, look) in listOf("curtains" to "cinema", "mountains" to "horizon", "lamp" to "library", "neon" to "arcade", "cobalt" to "console",
            "mist" to "mist", "diwali" to "diwali", "aurora" to "aurora")) {
            assertEquals(themes.getValue(look).wallpaper?.sha256, p.single { it.id == picture }.wallpaper.sha256, "$picture is $look's picture")
        }
    }

    // 0.4.0 Apps in a row. ---------------------------------------------------------------------

    private val looks = BundledThemeSource()
    private fun pkgs(n: Int) = List(n) { PackageName("com.example.app$it") }

    // 1. Every look at every offered count: the override wins, rows_below_dock and dock_title follow
    // it, not the look, and the bytes decode with no codec warning.
    @Test fun appsInARowWinsOverTheLookForEveryLook() {
        for (t in looks.bundled()) for (c in DockLayers.MIN_OFFERED_COLUMNS..DockLayers.MAX_OFFERED_COLUMNS) for (n in listOf(4, 6, 9, 15)) {
            val label = "${t.id} at $c with $n apps"
            val (doc, _) = Look.docOf(t, pkgs(n), cards = false, null, c)
            val back = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(LauncherConfigCodec.encode(doc)), label)
            assertEquals(emptyList(), back.warnings, "$label: no codec warning")
            val e = LauncherConfigCodec.effective(back.doc, LauncherConfigCodec.builtInDefaults)
            assertEquals(c, e.columns, label)
            val shown = minOf(n, 3 * c)
            val rows = DockLayers.rowsBelowDock(shown, c)
            assertEquals(if (t.doc.cinematicHome == true) 0 else rows, e.rowsBelowDock, label)
            assertEquals(pkgs(shown).map { it.value }, e.dockApps, label)
            assertEquals(false, e.fillDock, label)
            if (rows == 2 && t.doc.cinematicHome != true) assertEquals(false, e.dockTitle, "$label: three rows, no big name")
        }
    }

    // 2. With no override, each look's bytes are today's bytes plus one key, "fill_dock": false. A
    // JSON diff, so a failure names the key that changed.
    @Test fun withoutAnOverrideTheBytesOnlyAddFillDockFalse() {
        for (t in looks.bundled()) for (n in listOf(0, 2, 6, 15)) for (cards in listOf(true, false)) {
            val label = "${t.id} with $n apps, cards $cards"
            val before = LauncherConfigCodec.encode(DockLayers.applyTo(t.doc, pkgs(n)).copy(moduleCards = cards))
            val now = LauncherConfigCodec.encode(Look.docOf(t, pkgs(n), cards, null).first)
            val a = Json.parseToJsonElement(before.decodeToString()).jsonObject
            val b = Json.parseToJsonElement(now.decodeToString()).jsonObject
            assertEquals(setOf("fill_dock"), b.keys - a.keys, "$label: added keys")
            assertEquals(emptySet(), a.keys - b.keys, "$label: removed keys")
            assertEquals(emptyList(), a.keys.filter { a[it] != b[it] }, "$label: changed keys")
            assertEquals(JsonPrimitive(false), b["fill_dock"], label)
            assertEquals(LauncherConfigCodec.encode(Look.docOf(t, pkgs(n), cards, null, DockLayers.columnsOf(t.doc)).first).decodeToString(),
                now.decodeToString(), "$label: the look's own count is no override")
        }
    }

    // 8. home.txt: an old file loads with no apps in a row; 4 to 8 load, anything else is dropped; a
    // new file round trips.
    @Test fun homeTxtKeepsAppsInARow() = runTest {
        val h = Harness()
        val id = h.knownTv().id
        val prefs = HomePrefs(h.documents)
        h.documents.write("tvs/${id.value}/home.txt", "dock=com.netflix.ninja\ntheme=console\npicture=glass\n".encodeToByteArray())
        assertNull(prefs.load(id)?.columns)
        for ((text, want) in listOf("3" to null, "4" to 4, "8" to 8, "9" to null, "x" to null, "" to null)) {
            h.documents.write("tvs/${id.value}/home.txt", "dock=com.netflix.ninja\ntheme=console\ncolumns=$text\n".encodeToByteArray())
            assertEquals(want, prefs.load(id)?.columns, "columns=$text")
        }
        val choice = HomeChoice(pkgs(24), "mist", cards = true, picture = "glass", columns = 8)
        prefs.save(id, choice)
        assertEquals(choice, prefs.load(id))
        assertEquals(choice.copy(columns = null), choice.withTheme("mist", columns = null))
        assertEquals(8, choice.withTheme("console").columns, "a new look keeps a saved count unless told")
    }
}
