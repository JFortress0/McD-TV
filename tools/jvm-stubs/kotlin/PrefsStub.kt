package com.mcd.tv.data

// LOCAL-ONLY compile stub for tools/run-unit-tests.sh. The real Prefs (data/Prefs.kt) needs android.content.
// Only the members referenced by the main files the local run compiles (Live.kt, StreamInfo.kt).
// Unit tests must not depend on Prefs: in Gradle the real Prefs is uninitialized (lateinit SharedPreferences).
object Prefs {
    var m3uUrl: String = ""
    var slowConnection: Boolean = false
    var maxMovieGb: Int = 0
    var maxEpisodeGb: Int = 0
}
