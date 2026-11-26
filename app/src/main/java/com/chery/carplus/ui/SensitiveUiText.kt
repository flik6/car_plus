package com.chery.carplus.ui

/**
 * UI-only masking for vehicle and owner information.
 *
 * Values are transformed at the presentation boundary. No source, persistence or network
 * handling lives here; callers keep using the existing UI model for their own actions.
 */
internal object SensitiveUiText {
    const val MISSING = "—"
    private const val MASK = "••••"
    private val purchaseYear = Regex("^(?:19|20)\\d{2}(?=$|[-/.年 ]|\\d{4}$)")

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
}
