package com.chery.carplus.ui

/**
 * UI-only masking for vehicle, account, and owner information.
 *
 * Values are transformed at the presentation boundary. No source, persistence or network
 * handling lives here; callers keep using the existing UI model for their own actions.
 */
internal object SensitiveUiText {
    const val MISSING = "—"
    private const val MASK = "••••"
    private val purchaseYear = Regex("^(?:19|20)\\d{2}(?=$|[-/.年 ]|\\d{4}$)")
    private val countryPrefixRegex = Regex("^(\\+86|0086|86)[\\s-]*")
    private val phoneLikeRegex = Regex("^(?:\\+86|0086|86)?[\\s-]*(?:1[3-9]\\d{9}|1[3-9]\\d\\*{4,6}\\d{2,4})$")
    private val inlinePhoneRegex = Regex("(?<![0-9A-Za-z])(?:(\\+86|86)[\\s-]*)?(1[3-9]\\d(?:\\d{8}|\\*{4,6}\\d{2,4}))(?![0-9A-Za-z])")

    fun identifier(raw: String?): String {
        val clean = raw.orEmpty().trim()
        if (clean.isEmpty()) return MISSING
        return if (clean.length <= 4) MASK else MASK + clean.takeLast(4)
    }

    fun personName(raw: String?): String {
        val clean = raw.orEmpty().trim()
        return when (clean.codePointCount(0, clean.length)) {
            0 -> MISSING
            1 -> "•"
            else -> clean.substring(0, clean.offsetByCodePoints(0, 1)) + "••"
        }
    }

    fun year(raw: String?): String {
        return purchaseYear.find(raw.orEmpty().trim())?.value ?: MISSING
    }

    /**
     * Unified Qiri+ phone privacy masking (`前3后2，隐藏中间6位`):
     * - Standard 11-digit mobile (e.g. `13812345678`, `+86 13812345678`, `86-13812345678`,
     *   or legacy 4-asterisk masked values from backend): hides middle 6 digits -> `138******78` / `+86 138******78`.
     * - Unusual phone strings with length >= 6: shows first 3 + `*` × (len - 5) + last 2.
     * - Short phone strings (length == 5): shows first 2 + `*` + last 2.
     * - Length <= 4: returns `••••` (never exposes raw short phone fragments or 4-star masks).
     * - Null / blank: returns [emptyFallback] (default `""`).
     */
    fun maskPhone(raw: String?, emptyFallback: String = ""): String {
        val trimmed = raw.orEmpty().trim()
        if (trimmed.isEmpty()) return emptyFallback

        val prefixMatch = countryPrefixRegex.find(trimmed)
        val hasPlusPrefix = prefixMatch?.groupValues?.getOrNull(1) == "+86"
        val body = if (prefixMatch != null) trimmed.substring(prefixMatch.range.last + 1) else trimmed
        val core = body.replace(Regex("[\\s-]"), "")
        if (core.isEmpty()) return emptyFallback

        val displayPrefix = if (hasPlusPrefix) "+86 " else ""
        return when {
            core.length == 11 -> "$displayPrefix${core.take(3)}******${core.takeLast(2)}"
            core.length >= 6 -> "$displayPrefix${core.take(3)}${"*".repeat(core.length - 5)}${core.takeLast(2)}"
            core.length == 5 -> "$displayPrefix${core.take(2)}*${core.takeLast(2)}"
            else -> MASK
        }
    }

    /**
     * Masks [raw] via [maskPhone] only if it looks like a phone number (raw or legacy-masked);
     * otherwise returns the trimmed original string unchanged (e.g., `"demo"` or a nickname).
     */
    fun maskIfPhone(raw: String?, emptyFallback: String = ""): String {
        val trimmed = raw.orEmpty().trim()
        if (trimmed.isEmpty()) return emptyFallback
        return if (phoneLikeRegex.matches(trimmed)) maskPhone(trimmed, emptyFallback) else trimmed
    }

    /**
     * Masks all inline Chinese mobile numbers (raw 11-digit or legacy 4-star masked) embedded
     * inside diagnostic/log text using the `前3后2，隐藏中间6位` rule (`138******78`).
     */
    fun maskPhonesInText(text: String): String {
        if (text.isEmpty()) return text
        return inlinePhoneRegex.replace(text) { m -> maskPhone(m.value) }
    }
}
