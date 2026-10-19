package com.mcd.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpotifyCoreTest {
    private val pid = "37i9dQZF1DXcBWIGoYBM5M" // 22 characters, like every Spotify id
    private val tid = "4uLU6hMCjMI75M1A2tKUQC"
    private val me = "dev123abc"
    private val now = 1_792_353_600_000L

    // ------------------------------------------------------------------ URI parsing

    @Test
    fun parsesUrisAndShareLinks() {
        assertEquals("spotify:playlist:$pid", SpotifyUri.parse("spotify:playlist:$pid")?.uri)
        assertEquals("spotify:playlist:$pid", SpotifyUri.parse("  https://open.spotify.com/playlist/$pid?si=abc123  ")?.uri)
        assertEquals("spotify:playlist:$pid", SpotifyUri.parse("https://open.spotify.com/intl-de/playlist/$pid")?.uri)
        assertEquals("spotify:playlist:$pid", SpotifyUri.parse("https://open.spotify.com/embed/playlist/$pid#x")?.uri)
        assertEquals("spotify:playlist:$pid", SpotifyUri.parse("open.spotify.com/playlist/$pid")?.uri)
        assertEquals("spotify:playlist:$pid", SpotifyUri.parse("spotify:user:john:playlist:$pid")?.uri)
        assertEquals("spotify:album:$pid", SpotifyUri.parse("https://open.spotify.com/album/$pid")?.uri)
        assertEquals("spotify:track:$tid", SpotifyUri.parse("SPOTIFY:Track:$tid")?.uri)
    }

    @Test
    fun contextOrSingleItem() {
        assertTrue(SpotifyUri.parse("spotify:playlist:$pid")!!.isContext)
        assertTrue(SpotifyUri.parse("spotify:artist:$pid")!!.isContext)
        assertFalse(SpotifyUri.parse("spotify:track:$tid")!!.isContext)
        assertFalse(SpotifyUri.parse("spotify:episode:$tid")!!.isContext)
    }

    @Test
    fun rejectsWhatItCannotPlay() {
        listOf(
            null, "", "hello", "spotify:playlist:short", "spotify:playlist:$pid:extra",
            "spotify:user:john:collection", "spotify:local:a:b:c:1", "spotify:podcast:$pid",
            "https://spotify.link/abcdef", "https://evil.example/open.spotify.com/playlist/$pid",
            "https://open.spotify.com.evil.example/playlist/$pid", "https://open.spotify.com/playlist/",
            "javascript:alert(1)", "spotify:playlist:" + "a".repeat(400),
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5!",
        ).forEach { assertNull("should reject: $it", SpotifyUri.parse(it)) }
    }

    // ------------------------------------------------------------------ command routing

    private fun cmd(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { put(it.first, it.second) } }

    @Test
    fun playIsForTheNamedTvOnly() {
        val play = SpotifyCommand.route(cmd("cmd" to "spotify_play", "to" to me, "uri" to "spotify:playlist:$pid", "ts" to now), me, now)
        assertEquals(SpotifyCommand.Play(SpotifyUri.Ref("playlist", pid)), play)
        // The spec's "tv" field works as the target too.
        assertEquals(play, SpotifyCommand.route(cmd("cmd" to "spotify_play", "tv" to me, "uri" to "spotify:playlist:$pid"), me, now))
        // Another TV: ignored.
        assertNull(SpotifyCommand.route(cmd("cmd" to "spotify_play", "to" to "other", "uri" to "spotify:playlist:$pid"), me, now))
        assertNull(SpotifyCommand.route(cmd("cmd" to "spotify_play", "to" to me, "uri" to "spotify:playlist:$pid"), "", now))
    }

    @Test
    fun noTargetIsRefusedNeverPlayed() {
        val r = SpotifyCommand.route(cmd("cmd" to "spotify_play", "uri" to "spotify:playlist:$pid"), me, now)
        assertTrue(r is SpotifyCommand.Reject)
        val c = SpotifyCommand.route(cmd("cmd" to "spotify_control", "action" to "pause", "to" to " "), me, now)
        assertTrue(c is SpotifyCommand.Reject)
    }

    @Test
    fun badInputStaleAndOtherCommands() {
        assertEquals(
            SpotifyCommand.Reject("That is not a Spotify link."),
            SpotifyCommand.route(cmd("cmd" to "spotify_play", "to" to me, "uri" to "https://example.com"), me, now),
        )
        assertEquals(
            SpotifyCommand.Reject("Unknown control."),
            SpotifyCommand.route(cmd("cmd" to "spotify_control", "to" to me, "action" to "shuffle"), me, now),
        )
        assertNull(SpotifyCommand.route(cmd("cmd" to "spotify_play", "to" to me, "uri" to "spotify:playlist:$pid", "ts" to now - 11 * 60_000L), me, now))
        assertNull(SpotifyCommand.route(cmd("cmd" to "play", "to" to me), me, now))
        listOf("pause", "resume", "next", "previous").forEach {
            assertEquals(SpotifyCommand.Control(it), SpotifyCommand.route(cmd("cmd" to "spotify_control", "to" to me, "action" to it), me, now))
        }
        assertEquals(SpotifyCommand.Playlists(40), SpotifyCommand.route(cmd("cmd" to "spotify_list", "to" to me, "offset" to 40), me, now))
        assertEquals(SpotifyCommand.Playlists(0), SpotifyCommand.route(cmd("cmd" to "spotify_list", "to" to me, "offset" to -5), me, now))
    }

    // ------------------------------------------------------------------ playlist mapping

    private val page = JSONObject(
        """
        {"items":[
          {"id":"$pid","name":"  Gym Mix ","uri":"spotify:playlist:$pid","owner":{"display_name":"John"},
           "tracks":{"total":42},"images":[{"url":"https://i.scdn.co/image/abc"}]},
          null,
          {"id":"x","name":"Bad","uri":"spotify:album:$tid"},
          {"id":"$tid","name":"","uri":"spotify:playlist:$tid","items":{"total":7},"images":[{"url":"http://insecure"}]},
          {"name":"No uri"}
        ],"next":"https://api.spotify.com/v1/me/playlists?offset=50&limit=50","total":120}
        """.trimIndent(),
    )

    @Test
    fun mapsPlaylists() {
        val p = SpotifyMap.playlists(page)
        assertEquals(2, p.items.size)
        val gym = p.items[0]
        assertEquals("Gym Mix", gym.name)
        assertEquals("spotify:playlist:$pid", gym.uri)
        assertEquals("John", gym.owner)
        assertEquals(42, gym.tracks)
        assertEquals("https://i.scdn.co/image/abc", gym.image)
        assertEquals("Untitled playlist", p.items[1].name)
        assertEquals(7, p.items[1].tracks) // newer "items.total"
        assertNull(p.items[1].image) // http image dropped
        assertEquals("https://api.spotify.com/v1/me/playlists?offset=50&limit=50", p.next)
        assertEquals(120, p.total)
    }

    @Test
    fun mapsSearchAndEmptyAnswers() {
        assertEquals(2, SpotifyMap.searchPlaylists(JSONObject().put("playlists", page)).items.size)
        assertEquals(0, SpotifyMap.playlists(null).items.size)
        assertEquals(0, SpotifyMap.searchPlaylists(JSONObject()).items.size)
        assertNull(SpotifyMap.playlists(JSONObject().put("next", "https://evil.example/x")).next)
    }

    @Test
    fun playlistsForThePhoneFitTheRelay() {
        val many = (1..200).map { SpotifyMap.Playlist(pid, "Playlist number $it", "spotify:playlist:$pid", "", it, null) }
        val (a, n) = SpotifyMap.forPhone(many, 2400)
        assertTrue(n in 1 until 200)
        assertEquals(n, a.length())
        assertTrue(a.toString().length <= 2400)
        assertEquals("spotify:playlist:$pid", a.getJSONArray(0).getString(1))
    }

    @Test
    fun mapsPlayerAndDevices() {
        val s = SpotifyMap.player(
            JSONObject("""{"is_playing":true,"item":{"name":"Eye of the Tiger","uri":"spotify:track:$tid","artists":[{"name":"Survivor"},{"name":"X"}]}}"""),
            now,
        )!!
        assertEquals("Eye of the Tiger", s.track)
        assertEquals("Survivor, X", s.artist)
        assertTrue(s.playing)
        assertNull(SpotifyMap.player(null, now))
        assertNull(SpotifyMap.player(JSONObject("""{"is_playing":false,"item":null}"""), now))
        // Only this TV's device counts when it is known.
        val onGym = JSONObject("""{"is_playing":true,"device":{"id":"g"},"item":{"name":"Song","artists":[]}}""")
        assertEquals("Song", SpotifyMap.player(onGym, now, onDevice = "g")?.track)
        assertNull(SpotifyMap.player(onGym, now, onDevice = "phone"))

        val d = SpotifyMap.devices(JSONObject("""{"devices":[{"id":"a1","name":"Gym TV","type":"TV","is_active":false},{"id":null,"name":"Car","type":"Automobile","is_restricted":true}]}"""))
        assertEquals(2, d.size)
        assertEquals("", d[1].id)
        assertTrue(d[1].restricted)
    }

    // ------------------------------------------------------------------ route and device choice

    @Test
    fun picksTheRoute() {
        assertEquals(SpotifyRoute.Kind.APP_REMOTE, SpotifyRoute.choose(setOf("com.spotify.music", "com.spotify.tv.android")))
        assertEquals(SpotifyRoute.Kind.CONNECT, SpotifyRoute.choose(setOf("com.spotify.tv.android")))
        assertEquals(SpotifyRoute.Kind.NONE, SpotifyRoute.choose(setOf("com.example")))
    }

    @Test
    fun picksThisTvsConnectDevice() {
        val phone = SpotifyMap.Device("p", "John's iPhone", "Smartphone", true, false)
        val gym = SpotifyMap.Device("g", "Gym TV", "TV", false, false)
        val den = SpotifyMap.Device("d", "Den TV", "TV", false, false)
        assertEquals(gym, SpotifyRoute.pickDevice(listOf(phone, gym), "", emptyList()))
        assertNull(SpotifyRoute.pickDevice(listOf(phone, gym, den), "", emptyList())) // two TVs: ask
        assertEquals(den, SpotifyRoute.pickDevice(listOf(phone, gym, den), "den tv", emptyList()))
        assertEquals(gym, SpotifyRoute.pickDevice(listOf(phone, gym, den), "", listOf("Gym", "")))
        assertNull(SpotifyRoute.pickDevice(listOf(phone), "", emptyList()))
        assertNull(SpotifyRoute.pickDevice(listOf(gym.copy(id = "")), "", emptyList()))
    }

    // ------------------------------------------------------------------ config

    @Test
    fun clientIdIsCleanedAndControlPageWins() {
        val a = "0123456789abcdef0123456789abcdef"
        assertEquals(a, SpotifyConfig.cleanClientId("  0123456789ABCDEF0123456789abcdef \n"))
        assertEquals("", SpotifyConfig.cleanClientId("not a client id"))
        assertEquals("", SpotifyConfig.cleanClientId(a + "0"))
        assertEquals("", SpotifyConfig.cleanClientId(null))
        val b = "fedcba9876543210fedcba9876543210"
        assertEquals(a, SpotifyConfig.clientId(a, b))
        assertEquals(b, SpotifyConfig.clientId("", b))
        assertEquals(b, SpotifyConfig.clientId("junk", b))
        assertEquals("", SpotifyConfig.clientId("", ""))
    }

    @Test
    fun scopesAndProfiles() {
        assertEquals(
            setOf(
                "playlist-read-private", "playlist-read-collaborative", "user-library-read",
                "user-read-playback-state", "user-modify-playback-state", "app-remote-control",
            ),
            SpotifyConfig.SCOPES.toSet(),
        )
        assertTrue(SpotifyConfig.enabledFor("p1"))
        assertFalse(SpotifyConfig.enabledFor("p2"))
        assertFalse(SpotifyConfig.enabledFor("p3"))
    }

    @Test
    fun redirectUriMatchesTheManifest() {
        val root = System.getProperty("jarvis.mainSrc")?.let { File(it).parentFile } ?: File("app/src/main")
        val manifest = File(root, "AndroidManifest.xml").takeIf { it.exists() } ?: File("src/main/AndroidManifest.xml")
        val xml = manifest.readText()
        val scheme = SpotifyConfig.REDIRECT_URI.substringBefore("://")
        val host = SpotifyConfig.REDIRECT_URI.substringAfter("://")
        assertTrue(xml.contains("android:scheme=\"$scheme\""))
        assertTrue(xml.contains("android:host=\"$host\""))
    }

    // ------------------------------------------------------------------ tokens and redaction

    @Test
    fun tokensRoundTripAndRefresh() {
        val t = SpotifyTokens("AQaccess", "AQrefresh", now + 3_600_000L, "playlist-read-private")
        assertEquals(t, SpotifyTokens.fromJson(t.toJson()))
        assertNull(SpotifyTokens.fromJson("{}"))
        assertNull(SpotifyTokens.fromJson("not json"))
        assertNull(SpotifyTokens.fromJson(null))
        assertFalse(t.needsRefresh(now))
        assertTrue(t.needsRefresh(now + 3_550_000L))
        assertFalse(t.copy(refresh = "").needsRefresh(now + 9_999_999L))

        val same = t.refreshed(JSONObject().put("access_token", "AQnew").put("expires_in", 3600), now)!!
        assertEquals("AQnew", same.access)
        assertEquals("AQrefresh", same.refresh) // no new refresh token: keep the old one
        val rotated = t.refreshed(JSONObject().put("access_token", "AQnew").put("refresh_token", "AQrot"), now)!!
        assertEquals("AQrot", rotated.refresh)
        assertNull(t.refreshed(JSONObject().put("error", "invalid_grant"), now))
    }

    @Test
    fun tokensNeverPrint() {
        val t = SpotifyTokens("AQsecretAccess", "AQsecretRefresh", now)
        val s = t.toString() + " " + "$t"
        assertFalse(s.contains("AQsecret"))
    }

    @Test
    fun redactsSecretsInText() {
        val raw = """POST grant_type=refresh_token&refresh_token=AQr3fr3sh&client_id=abc {"access_token":"BQacc","token_type":"Bearer"} """ +
            "Authorization: Bearer BQheader.x-y_z code=AQcode&code_verifier=v3r1f13r client_secret=s3cr3t"
        val out = SpotifyRedact.text(raw)
        listOf("AQr3fr3sh", "BQacc", "BQheader", "AQcode", "v3r1f13r", "s3cr3t").forEach { assertFalse("leaked $it: $out", out.contains(it)) }
        assertTrue(out.contains("client_id=abc")) // the Client ID is not a secret
        assertTrue(out.contains("grant_type=refresh_token"))
        assertEquals("", SpotifyRedact.text(null))
        assertEquals("Playlist not found", SpotifyRedact.text("Playlist not found"))
    }

    // ------------------------------------------------------------------ music status

    @Test
    fun musicStatusJsonAndChanges() {
        val a = MusicStatus.State(track = "Eye of the Tiger", artist = "Survivor", playing = true, uri = "spotify:track:$tid", ts = now)
        val j = MusicStatus.json(a)!!
        assertEquals("spotify", j.getString("source"))
        assertEquals("Eye of the Tiger", j.getString("track"))
        assertEquals("Survivor", j.getString("artist"))
        assertTrue(j.getBoolean("playing"))
        assertFalse(j.has("uri"))
        assertNull(MusicStatus.json(null))
        assertTrue(MusicStatus.changed(null, a))
        assertTrue(MusicStatus.changed(a, a.copy(playing = false)))
        assertTrue(MusicStatus.changed(a, a.copy(track = "Next")))
        assertFalse(MusicStatus.changed(a, a.copy(ts = now + 5000)))

        var heard = 0
        MusicStatus.onChange { heard++ }
        MusicStatus.set(a)
        MusicStatus.set(a.copy(ts = now + 1)) // same song, same state: no new event
        MusicStatus.clear()
        assertEquals(2, heard)
        MusicStatus.onChange(null)
    }
}
