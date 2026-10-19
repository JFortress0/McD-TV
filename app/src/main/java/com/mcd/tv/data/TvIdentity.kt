package com.mcd.tv.data

/**
 * Who this TV is, for phones and the Control page: a name J gives it ("Living Room TV", "Office TV", "The Wall")
 * and the platform it runs on. Pure logic (no Android); callers pass Build.MANUFACTURER and Build.MODEL.
 * Tests in TvIdentityTest.
 */
object TvIdentity {
    const val LABEL_MAX = 40

    /** Names offered when the label is empty. */
    val EXAMPLES = listOf("Living Room TV", "Office TV", "The Wall")

    /**
     * Platform by exact model, for models read from a real device only (a tv_link it posted, or J's report).
     * Do not add models from memory or vendor lists. Unknown models fall back to the maker rule below, and the
     * model itself is shown next to the platform so it can be added here later.
     */
    private val KNOWN_MODELS = mapOf(
        "AFTHY7ABBA" to "Fire TV", // J's Office TV (Oct 18, 2026): a Fire TV, not a stick
    )

    /** "Fire TV", "Google TV" or "Android TV"; a known model wins over the maker rule. */
    fun platform(maker: String, model: String): String {
        KNOWN_MODELS[model.trim().uppercase()]?.let { return it }
        return when {
            maker.equals("Amazon", ignoreCase = true) -> "Fire TV"
            maker.equals("Google", ignoreCase = true) || model.contains("Chromecast", ignoreCase = true) -> "Google TV"
            model.isBlank() && maker.isBlank() -> "TV"
            else -> "Android TV"
        }
    }

    /** True when the exact model is in [KNOWN_MODELS]. The UI shows the raw model for the others. */
    fun modelKnown(model: String): Boolean = model.trim().uppercase() in KNOWN_MODELS

    /** The label as stored: one line, trimmed, at most [LABEL_MAX] characters. */
    fun cleanLabel(label: String): String = label.replace(Regex("\\s+"), " ").trim().take(LABEL_MAX).trim()

    /** What people see: the label when there is one, else the platform. */
    fun displayName(label: String, platform: String): String = cleanLabel(label).ifEmpty { platform.ifBlank { "TV" } }

    /** "Office TV (Fire TV)" for lists; just the platform when no label is set. */
    fun listName(label: String, platform: String): String {
        val l = cleanLabel(label)
        return if (l.isEmpty()) platform.ifBlank { "TV" } else "$l ($platform)"
    }
}
