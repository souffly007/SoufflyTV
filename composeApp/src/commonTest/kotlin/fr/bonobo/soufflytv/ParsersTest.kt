package fr.bonobo.soufflytv

import fr.bonobo.soufflytv.data.HOUR_MS
import fr.bonobo.soufflytv.data.categoryGroup
import fr.bonobo.soufflytv.data.groupCategories
import fr.bonobo.soufflytv.model.Category
import fr.bonobo.soufflytv.data.M3uParser
import fr.bonobo.soufflytv.data.XmltvParser
import fr.bonobo.soufflytv.data.decodeBase64OrRaw
import fr.bonobo.soufflytv.data.epgBaseName
import fr.bonobo.soufflytv.data.extractStreamUrl
import fr.bonobo.soufflytv.data.formatDuration
import fr.bonobo.soufflytv.data.isAdultCategory
import fr.bonobo.soufflytv.data.isValidMac
import fr.bonobo.soufflytv.data.normalizeChannelName
import fr.bonobo.soufflytv.data.normalizeMac
import fr.bonobo.soufflytv.data.parseXmltvTime
import fr.bonobo.soufflytv.data.parseXtreamUrl
import fr.bonobo.soufflytv.data.stalkerEndpoints
import fr.bonobo.soufflytv.model.ItemType
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.data.AccountBackup
import fr.bonobo.soufflytv.data.AccountBackupCodec
import fr.bonobo.soufflytv.data.BackupException
import fr.bonobo.soufflytv.data.backupFileName
import fr.bonobo.soufflytv.data.civilFromDays
import fr.bonobo.soufflytv.data.movieMetaLine
import fr.bonobo.soufflytv.data.parseRuntimeMinutes
import fr.bonobo.soufflytv.data.ratingValue
import fr.bonobo.soufflytv.data.runtimeLabel
import fr.bonobo.soufflytv.player.TrackOption
import fr.bonobo.soufflytv.player.isAudioDescription
import fr.bonobo.soufflytv.player.pickAudioTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsersTest {

    @Test
    fun m3uExtended() {
        val text = """
            #EXTM3U url-tvg="http://g/a.xml.gz, http://g/b.xml"
            #EXTINF:-1 tvg-id="a" tvg-chno="5" tvg-logo="http://logo/a.png" group-title="Infos, Monde",Chaîne A, HD
            #EXTVLCOPT:http-user-agent=TestUA
            http://serveur/a.ts
            #EXTINF:-1,Chaîne B
            #EXTGRP:Sport
            http://serveur/b.m3u8
            http://serveur/movie/u/p/sans-titre.mkv
        """.trimIndent()

        val playlist = M3uParser.parse(text)
        val list = playlist.channels
        assertEquals(listOf("http://g/a.xml.gz", "http://g/b.xml"), playlist.epgUrls)
        assertEquals(3, list.size)

        val a = list[0]
        assertEquals("Chaîne A, HD", a.name)
        assertEquals("Infos, Monde", a.categoryId)
        assertEquals("http://logo/a.png", a.logo)
        assertEquals(5, a.number)
        assertEquals("a", a.epgId)
        assertEquals("TestUA", a.headers["User-Agent"])
        assertEquals(ItemType.LIVE, a.type)

        assertEquals("Sport", list[1].categoryId)
        assertTrue(list[1].headers.isEmpty())

        assertEquals("sans-titre.mkv", list[2].name)
        assertEquals(M3uParser.NO_GROUP, list[2].categoryId)
        assertEquals(ItemType.MOVIE, list[2].type)

        // identifiant stable : les favoris survivent à un rechargement
        assertEquals(a.id, M3uParser.parse(text).channels[0].id)
    }

    @Test
    fun xmltvTimes() {
        assertEquals(0L, parseXmltvTime("19700101000000 +0000"))
        assertEquals(1_763_495_100_000L, parseXmltvTime("20251118204500 +0100"))
        assertEquals(parseXmltvTime("20240301043000 +0000"), parseXmltvTime("20240229233000 -0500"))
        assertNull(parseXmltvTime("2026"))
    }

    @Test
    fun xmltvStreaming() {
        val xml = """<tv>
          <channel id="TF1.fr"><display-name>TF1</display-name></channel>
          <channel id="m6.fr"><display-name>M6 HD</display-name></channel>
          <programme start="20261118200000 +0100" stop="20261118204500 +0100" channel="TF1.fr"><title>Le 20h &amp; météo</title><desc>Infos</desc></programme>
          <programme start="20261118210000 +0100" stop="20261118220000 +0100" channel="m6.fr"><title>Caf&#233;</title></programme>
          <programme start="20261118210000 +0100" stop="20261118220000 +0100" channel="autre"><title>Ignoré</title></programme>
        </tv>"""
        val from = parseXmltvTime("20261118000000 +0100")!!
        val parser = XmltvParser(setOf("tf1.fr"), setOf(normalizeChannelName("M6")), from, from + 48 * HOUR_MS)
        xml.chunked(23).forEach { parser.feed(it) } // découpage arbitraire, comme sur le réseau
        val result = parser.finish()
        assertEquals("Le 20h & météo", result["tf1.fr"]?.single()?.title)
        assertEquals("Infos", result["tf1.fr"]?.single()?.description)
        assertEquals("m6.fr", parser.idForName(normalizeChannelName("M6")))
        assertEquals("Café", result["m6.fr"]?.single()?.title)
        assertNull(result["autre"])
    }

    @Test
    fun macAddress() {
        assertEquals("00:1A:79:AB:CD:EF", normalizeMac("001a79abcdef"))
        assertEquals("00:1A:79:AB:CD:EF", normalizeMac("00-1a-79-ab-cd-ef"))
        assertTrue(isValidMac("00:1A:79:AB:CD:EF"))
        assertFalse(isValidMac("00:1A:79:AB:CD"))
        assertFalse(isValidMac("00:1A:79:AB:CD:XZ"))
    }

    @Test
    fun stalkerEndpointGuessing() {
        assertEquals(
            listOf(
                "http://p.example:8080/server/load.php",
                "http://p.example:8080/portal.php",
                "http://p.example:8080/stalker_portal/server/load.php",
            ),
            stalkerEndpoints("p.example:8080/c/"),
        )
        assertEquals(
            "http://p.example/stalker_portal/server/load.php",
            stalkerEndpoints("http://p.example/stalker_portal/c/").first(),
        )
        assertEquals(listOf("http://p.example/portal.php"), stalkerEndpoints("http://p.example/portal.php"))
    }

    @Test
    fun stalkerCommand() {
        assertEquals("http://h/live/1.ts", extractStreamUrl("ffmpeg http://h/live/1.ts"))
        assertEquals("http://h/x", extractStreamUrl("http://h/x"))
        assertNull(extractStreamUrl("ffmpeg"))
    }

    @Test
    fun xtreamHelpers() {
        val parsed = parseXtreamUrl("http://x.example:8080/get.php?username=bob&password=secret&type=m3u_plus")
        assertEquals(Triple("http://x.example:8080", "bob", "secret"), parsed)
        assertNull(parseXtreamUrl("http://x.example:8080"))
        assertEquals("Le 20h de TF1", decodeBase64OrRaw("TGUgMjBoIGRlIFRGMQ=="))
        assertEquals("Journal de 20h", decodeBase64OrRaw("Journal de 20h"))
    }

    @Test
    fun misc() {
        assertTrue(isAdultCategory("XXX | Adult"))
        assertTrue(isAdultCategory("FR: Adultes +18"))
        assertFalse(isAdultCategory("Sport"))
        assertEquals("1:02:03", formatDuration(3_723_000))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("tf1", normalizeChannelName("TF1 HD"))
    }

    @Test
    fun countryGroups() {
        assertEquals("FR", categoryGroup("FR: Sport"))
        assertEquals("FR", categoryGroup("|FR| TF1 HD"))
        assertEquals("UK", categoryGroup("[UK] News"))
        assertEquals("AR", categoryGroup("AR-Sports"))
        assertEquals("DE", categoryGroup("DE Kino"))
        assertNull(categoryGroup("Documentaires"))
        assertNull(categoryGroup("TF1"))
        val groups = groupCategories(listOf("FR: A", "FR: B", "UK: A", "UK: B", "UK: C", "DE: seul").mapIndexed { i, n -> Category("$i", n) })
        assertEquals(listOf("UK", "FR"), groups.map { it.name })
    }

    @Test
    fun channelVariants() {
        assertEquals("tf1", epgBaseName("|FR| TF1 SD"))
        assertEquals("tf1", epgBaseName("|FR| TF1 4K HDR UHD (Résolution Exclus)"))
        assertEquals("france2", epgBaseName("|FR| FRANCE 2 SD"))
        assertEquals("tf1seriesfilms", epgBaseName("|FR| TF1 SERIES FILMS HD"))
        assertEquals("canalsport", epgBaseName("|FR| CANAL+ SPORT FHD"))
    }

    @Test
    fun audioDescription() {
        assertTrue(isAudioDescription("Audiodescription", null))
        assertTrue(isAudioDescription("Français (AD)", "fra"))
        assertTrue(isAudioDescription(null, "qad"))
        assertTrue(isAudioDescription("Piste 2 - [qad]", null))
        assertFalse(isAudioDescription("Français", "fra"))
        assertFalse(isAudioDescription("Adventure", null))

        val tracks = listOf(
            TrackOption("0", "Français", selected = false, language = "fra"),
            TrackOption("1", "Français AD", selected = true, language = "fra", audioDescription = true),
            TrackOption("2", "Anglais", selected = false, language = "eng"),
        )
        assertEquals("0", pickAudioTrack(tracks, wantAudioDescription = false)?.id)
        assertEquals(null, pickAudioTrack(tracks, wantAudioDescription = true))
    }

    @Test
    fun accountBackup() {
        assertEquals("SoufflyTV-comptes-1970-01-01.json", backupFileName(0))
        assertEquals(Triple(2024, 2, 29), civilFromDays(19_782))

        val sources = listOf(
            Source.Xtream("x1", "Mon Xtream", "http://exemple.com:8080", "franck", "motdepasse"),
            Source.M3u("m1", "Playlist", "http://exemple.com/liste.m3u", null, null),
        )
        val text = AccountBackupCodec.encode(AccountBackup(1L, sources, mapOf("p.m3u" to "#EXTM3U")), "secret123")
        assertFalse("motdepasse" in text) // identifiants illisibles sans le mot de passe
        val restored = AccountBackupCodec.decode(text, "secret123")
        assertEquals(sources, restored.sources)
        assertEquals("#EXTM3U", restored.playlists["p.m3u"])
        assertFailsWith<BackupException> { AccountBackupCodec.decode(text, "mauvais") }
        assertFailsWith<BackupException> { AccountBackupCodec.decode("pas une sauvegarde", "secret123") }
    }

    @Test
    fun movieDetails() {
        assertEquals("1 h 57", runtimeLabel(117))
        assertEquals("45 min", runtimeLabel(45))
        assertEquals(117, parseRuntimeMinutes("01:57:10"))
        assertEquals(null, parseRuntimeMinutes("N/A"))
        assertEquals("2014 · Action · 1 h 57 · ★ 6,1", movieMetaLine("2014-02-12", "Action", 117, "6.1"))
        assertEquals(null, movieMetaLine(null, "", null, "0"))
        assertEquals("6,9", ratingValue("6.9"))
        assertEquals("10,0", ratingValue("10"))
        assertEquals(null, ratingValue("N/A"))
    }
}
