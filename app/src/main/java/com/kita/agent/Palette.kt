package com.kita.agent

internal object Palette {
    val BRAND = 0xFF0A74D0.toInt()
    val BRAND_2 = 0xFF00A0E8.toInt()
    val BRAND_DEEP = 0xFF065AA8.toInt()
    val BLUE = 0xFF3B82F6.toInt()
    val VIOLET = 0xFF8B5CF6.toInt()
    val TEAL = 0xFF0D9488.toInt()
    val GREEN = 0xFF16A34A.toInt()
    val AMBER = 0xFFF59E0B.toInt()
    val ORANGE = 0xFFF97316.toInt()
    val RED = 0xFFEF4444.toInt()
    val CYAN = 0xFF0891B2.toInt()
    val PINK = 0xFFEC4899.toInt()
    val INDIGO = 0xFF6366F1.toInt()
    val SLATE = 0xFF475569.toInt()
    val TEXT_MAIN = 0xFF1E2433.toInt()
    val TEXT_SOFT = 0xFF6B7385.toInt()
    val BG = 0xFFEEF6FD.toInt()
    val SURFACE = 0xFFFFFFFF.toInt()
    val BORDER = 0xFFE4E7F0.toInt()
    val INK = 0xFF1F1F1F.toInt()
    val INK_SOFT = 0xFF6B7280.toInt()
    val BUBBLE = 0xFFE8F0FE.toInt()
    val FIELD = 0xFFF3F5F9.toInt()
    val DIVIDER = 0xFFE8EAEF.toInt()
    val SKY_TOP = 0xFF085DAD.toInt()
    val SKY_MID = 0xFF0092D7.toInt()
    val SKY_BOTTOM = 0xFF00B6F7.toInt()
    val SKY_COLORS = intArrayOf(SKY_TOP, 0xFF0272BE.toInt(), SKY_MID, 0xFF00A8E6.toInt(), SKY_BOTTOM)
    val SKY_STOPS = floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f)
    val BAR = 0xFFDDF0FD.toInt()
    val NAV = 0xFFF5F5F5.toInt()
    val BUBBLE_ME = 0xFF0C1C27.toInt()
    val BUBBLE_AI = 0xFFFFFFFF.toInt()
    val ON_BLUE = 0xFF07121C.toInt()
    val ON_BLUE_SOFT = 0xCCFFFFFF.toInt()
    val TIME_ME = 0xB3FFFFFF.toInt()
    val TIME_AI = 0xFF8A93A0.toInt()
    val TICK = 0xFF29B6F6.toInt()
    val BALL_CORE = 0xFFFFD27A.toInt()
    val BALL = 0xFFFF9A1F.toInt()
    val BALL_EDGE = 0xFFF26B00.toInt()
    val CODE_HEAD = 0xFF182935.toInt()
    val CODE_BODY = 0xFF0C171E.toInt()
    val CODE_GUTTER = 0xFF111F2A.toInt()
    val CODE_NUM = 0xFF5F7384.toInt()
    val CARD = 0xFFF5F7FB.toInt()
    val STEP_DONE = 0xFF22A745.toInt()
    val STEP_BUSY = 0xFF1E88E5.toInt()
}

internal fun tint(color: Int, amount: Float): Int {
    fun mix(ch: Int) = (ch + (255 - ch) * amount.coerceIn(0f, 1f) + 0.5f).toInt()
    val r = mix((color shr 16) and 0xFF)
    val g = mix((color shr 8) and 0xFF)
    val b = mix(color and 0xFF)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

internal fun onColor(bg: Int): Int {
    val r = (bg shr 16) and 0xFF
    val g = (bg shr 8) and 0xFF
    val b = bg and 0xFF
    val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return if (lum > 0.62) 0xFF1E2433.toInt() else 0xFFFFFFFF.toInt()
}

internal fun stripIcons(s: String): String = s.replace(Regex("[\\u2705\\u274C\\u2753\\u26A0\\u23F3\\u231B\\uFE0F\\u200D\\x{1F300}-\\x{1FAFF}]"), "")