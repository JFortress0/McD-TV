package com.mcd.tv.data

/**
 * What a source's name and title say about it, for the one-line source rows:
 * quality tier (stars), resolution, HDR, audio, size and whether it plays instantly.
 */
data class StreamInfo(
    /** 1..5: 5 = REMUX or 4K BluRay, 4 = 1080p+ BluRay / WEB-DL, 3 = WEBRip / HDTV, 2 and below = lower. */
    val stars: Int,
    /** "4K", "1080p", "720p", "SD" or "" when unknown. */
    val resolution: String,
    /** "REMUX", "BluRay", "WEB-DL", "WEBRip", "HDTV", "DVD", "CAM" or "". */
    val source: String,
    /** "DV", "HDR10+", "HDR" (any that apply). */
    val hdr: List<String>,
    /** e.g. "Atmos", "DD+ 5.1", "DTS", "5.1", or "". */
    val audio: String,
    val sizeGb: Double,
    val sizeLabel: String,
    /** Debrid-cached: plays right away. */
    val instant: Boolean,
    /** A torrent (or debrid download link) that is not cached: has to download first. */
    val download: Boolean,
    /** CAM / TS / TELESYNC / screener release. */
    val cam: Boolean,
    /** Duplicate check: same torrent file, or same file name and size. */
    val dedupeKey: String,
) {
    val resolutionRank: Int get() = when (resolution) { "4K" -> 4; "1080p" -> 3; "720p" -> 2; "SD" -> 1; else -> 0 }

    companion object {
        private val opt = setOf(RegexOption.IGNORE_CASE)
        private val res4k = Regex("(2160p|\\b4k\\b|\\buhd\\b)", opt)
        private val res1080 = Regex("1080[pi]", opt)
        private val res720 = Regex("720p", opt)
        private val resSd = Regex("(480p|576p|360p|\\bsd\\b|dvdrip)", opt)
        private val remux = Regex("remux", opt)
        private val bluray = Regex("(blu-?ray|bdrip|brrip|\\bbd(25|50|66|100)?\\b)", opt)
        private val webdl = Regex("(web-?dl|\\bwebdl\\b|\\bweb\\b|amzn|\\bnf\\b|dsnp|hmax)", opt)
        private val webrip = Regex("web-?rip", opt)
        private val hdtv = Regex("(hdtv|pdtv|\\btvrip\\b)", opt)
        private val dvd = Regex("\\bdvd", opt)
        private val camRx = Regex(
            "\\b(cam|camrip|hdcam|ts|hdts|telesync|tc|hdtc|telecine|scr|screener|dvdscr|bdscr)\\b",
            opt,
        )
        private val dv = Regex("(\\bdv\\b|\\bdovi\\b|dolby[ ._-]?vision)", opt)
        private val hdr10p = Regex("hdr10(\\+|plus)", opt)
        private val hdr = Regex("\\bhdr(10)?\\b", opt)
        private val atmos = Regex("atmos", opt)
        private val truehd = Regex("true-?hd", opt)
        private val dts = Regex("\\bdts", opt)
        private val ddp = Regex("(ddp|dd\\+|e-?ac-?3)", opt)
        // "DDP5.1", "AAC 5.1", "DTS-HD.MA.7.1"; not "15.1 GB" or "1.5.1" (digit, or digit + dot, before it).
        private val channels = Regex("(?<![0-9])(?<![0-9]\\.)([57])[ .]1(?![0-9])(?!\\s?(gb|mb|gib|mib))", opt)
        private val nonAlnum = Regex("[^a-z0-9]+")

        fun parse(s: StreamSource): StreamInfo {
            val text = "${s.name} ${s.title}"
            val resolution = when {
                res4k.containsMatchIn(text) -> "4K"
                res1080.containsMatchIn(text) -> "1080p"
                res720.containsMatchIn(text) -> "720p"
                resSd.containsMatchIn(text) -> "SD"
                s.quality == "4K" || s.quality == "1080p" || s.quality == "720p" -> s.quality
                s.quality == "480p" -> "SD"
                else -> ""
            }
            val cam = camRx.containsMatchIn(text)
            val source = when {
                remux.containsMatchIn(text) -> "REMUX"
                webrip.containsMatchIn(text) -> "WEBRip"
                bluray.containsMatchIn(text) -> "BluRay"
                webdl.containsMatchIn(text) -> "WEB-DL"
                hdtv.containsMatchIn(text) -> "HDTV"
                cam -> "CAM"
                dvd.containsMatchIn(text) -> "DVD"
                else -> ""
            }
            val hi = resolution == "4K" || resolution == "1080p"
            val stars = when {
                cam -> 1
                source == "REMUX" -> 5
                source == "BluRay" && resolution == "4K" -> 5
                (source == "BluRay" || source == "WEB-DL") && hi -> 4
                (source == "WEBRip" || source == "HDTV") && resolution == "SD" -> 2
                source == "WEBRip" || source == "HDTV" -> 3
                source == "BluRay" || source == "WEB-DL" -> 3
                hi -> 3
                resolution == "720p" || source == "DVD" -> 2
                else -> 1
            }
            val hdrList = buildList {
                if (dv.containsMatchIn(text)) add("DV")
                if (hdr10p.containsMatchIn(text)) add("HDR10+") else if (hdr.containsMatchIn(text)) add("HDR")
            }
            val codec = when {
                atmos.containsMatchIn(text) -> "Atmos"
                truehd.containsMatchIn(text) -> "TrueHD"
                dts.containsMatchIn(text) -> "DTS"
                ddp.containsMatchIn(text) -> "DD+"
                else -> null
            }
            val ch = channels.find(text)?.groupValues?.get(1)?.let { "$it.1" }
            val audio = if (codec == "Atmos") codec else listOfNotNull(codec, ch).joinToString(" ")
            val sizeLabel = when {
                s.sizeGb >= 1.0 -> "%.1f GB".format(s.sizeGb)
                s.sizeGb > 0.0 -> "${(s.sizeGb * 1024).toInt()} MB"
                else -> s.sizeText
            }
            val notCachedLink = s.url != null && s.name.contains("download", ignoreCase = true)
            val hash = s.infoHash?.lowercase()
            val fileName = s.title.lineSequence().firstOrNull { it.isNotBlank() }?.lowercase()?.replace(nonAlnum, "") ?: ""
            val dedupeKey = when {
                hash != null -> "h:$hash:${s.fileIdx ?: -1}"
                fileName.length >= 8 && s.sizeGb > 0 -> "f:$fileName:${"%.1f".format(s.sizeGb)}"
                else -> "u:${s.url ?: (s.addon + s.name + s.title)}"
            }
            return StreamInfo(
                stars = stars,
                resolution = resolution,
                source = source,
                hdr = hdrList,
                audio = audio,
                sizeGb = s.sizeGb,
                sizeLabel = sizeLabel,
                instant = s.cached,
                download = !s.cached && (hash != null || notCachedLink),
                cam = cam,
                dedupeKey = dedupeKey,
            )
        }

        /** Cached first, then stars, resolution and size. Slow connection: 720p/1080p and smaller files first. */
        private fun comparator(): Comparator<RankedStream> {
            val slow = Prefs.slowConnection
            return if (slow) {
                compareByDescending<RankedStream> { it.info.instant }
                    .thenByDescending { when (it.info.resolution) { "720p" -> 3; "1080p" -> 2; else -> 0 } }
                    .thenBy { if (it.info.sizeGb > 0) it.info.sizeGb else Double.MAX_VALUE }
                    .thenByDescending { it.info.stars }
            } else {
                compareByDescending<RankedStream> { it.info.instant }
                    .thenByDescending { it.info.stars }
                    .thenByDescending { it.info.resolutionRank }
                    .thenByDescending { it.info.sizeGb }
            }
        }

        /** Every source parsed, sorted and de-duplicated (the best copy of a duplicate wins). */
        fun ranked(list: List<StreamSource>): List<RankedStream> =
            list.map { RankedStream(it, parse(it)) }.sortedWith(comparator()).distinctBy { it.info.dedupeKey }

        /**
         * Sorted, de-duplicated sources split into what the source list shows by default and what it hides:
         * CAM / TS / screener releases, and files over the size limit (Prefs.maxMovieGb / maxEpisodeGb, 0 = none).
         */
        fun arrange(list: List<StreamSource>, episode: Boolean): ArrangedStreams {
            val limit = if (episode) Prefs.maxEpisodeGb else Prefs.maxMovieGb
            val (hidden, visible) = ranked(list).partition { r ->
                r.info.cam || (limit > 0 && r.info.sizeGb > limit)
            }
            return ArrangedStreams(visible, hidden)
        }

        /** The order Play uses: the shown sources, or (if every one is hidden) the hidden ones. */
        fun playOrder(list: List<StreamSource>, episode: Boolean): List<StreamSource> {
            val a = arrange(list, episode)
            return a.visible.ifEmpty { a.hidden }.map { it.source }
        }
    }
}

data class RankedStream(val source: StreamSource, val info: StreamInfo)

data class ArrangedStreams(val visible: List<RankedStream>, val hidden: List<RankedStream>)
