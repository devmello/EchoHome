package app.vesper.core.design

import android.graphics.Color
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * Design tokens from NOTES §3. All sizes in the designs are device px at 960×480 and map 1:1 on
 * the Echo (density 195, so don't use dp for spec values — Views take px).
 */
object VesperColors {
    const val BLACK = Color.BLACK
    const val WHITE = Color.WHITE
    /** Secondary text: rgba(255,255,255,0.6–0.78). */
    val SECONDARY = withAlpha(WHITE, 0.6f)
    val SECONDARY_STRONG = withAlpha(WHITE, 0.78f)

    /** Apple system dark colours (accent swatches). */
    val RED = 0xFFFF453A.toInt()
    val ORANGE = 0xFFFF9F0A.toInt()
    val YELLOW = 0xFFFFD60A.toInt()
    val GREEN = 0xFF30D158.toInt()
    val CYAN = 0xFF64D2FF.toInt()
    val BLUE = 0xFF0A84FF.toInt()
    val PURPLE = 0xFFBF5AF2.toInt()
    val PINK = 0xFFFF375F.toInt()

    /** Edit-mode colour capsule options. */
    val SWATCHES = listOf(WHITE, RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE, PINK)

    /** Tint for the one primary action on a surface. */
    val PRIMARY = BLUE
    /** Alarm and timers. */
    val ALARM = ORANGE
    val SUCCESS = GREEN
}

/** Night mode (NOTES §3.3, §5.7): every colour becomes #BE180E, the face is dimmed to ~0.42. */
object Night {
    val COLOR = 0xFFBE180E.toInt()
    const val DIM = 0.42f
    /** Seconds a tap/motion wakes the face to full brightness. */
    const val WAKE_SECONDS = 6

    /** Maps a colour for night: keeps its alpha, replaces the RGB. */
    fun map(color: Int, night: Boolean): Int = if (!night) color else (color and 0xFF000000.toInt()) or (COLOR and 0x00FFFFFF)
}

/** Motion (NOTES §3.5). */
object Motion {
    /** cubic-bezier(0.3, 1.4, 0.5, 1): presses, art scaling, sheets. */
    val SPRING: Interpolator = PathInterpolator(0.3f, 1.4f, 0.5f, 1f)
    /** cubic-bezier(0.3, 1.35, 0.5, 1): softer overshoot. */
    val SPRING_SOFT: Interpolator = PathInterpolator(0.3f, 1.35f, 0.5f, 1f)
    /** cubic-bezier(0.3, 1.5, 0.5, 1): bouncier. */
    val SPRING_BOUNCY: Interpolator = PathInterpolator(0.3f, 1.5f, 0.5f, 1f)
    /** Island bounds + radius: cubic-bezier(0.3, 1.32, 0.5, 1) over 0.6 s (NOTES §5.4). */
    val ISLAND: Interpolator = PathInterpolator(0.3f, 1.32f, 0.5f, 1f)
    const val ISLAND_MS = 600L
    /** Expanded island content fades in this much later. */
    const val ISLAND_CONTENT_DELAY_MS = 150L
    /** Standard ease for fades. */
    val EASE: Interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
}

/** The glass recipe (NOTES §3.2). */
object GlassTokens {
    val FILL = withAlpha(Color.WHITE, 0.09f)
    val BORDER = withAlpha(Color.WHITE, 0.22f)
    const val BORDER_WIDTH = 0.5f
    const val RIM_TOP_ALPHA = 0.42f
    const val RIM_BOTTOM_ALPHA = 0.08f
    /** backdrop-filter: blur(16px) saturate(1.8) brightness(1.15). */
    const val BLUR_PX = 16f
    const val SATURATE = 1.8f
    const val BRIGHTNESS = 1.15f
    /** box-shadow 0 10px 30px rgba(0,0,0,0.45). */
    const val SHADOW_DY = 10f
    const val SHADOW_BLUR = 30f
    const val SHADOW_ALPHA = 0.45f
    /** Specular overlay: linear-gradient(160deg, .26 0%, .04 36%, 0 64%, .07 100%). */
    const val SPECULAR_ANGLE = 160f
    val SPECULAR_STOPS = floatArrayOf(0f, 0.36f, 0.64f, 1f)
    val SPECULAR_ALPHAS = floatArrayOf(0.26f, 0.04f, 0f, 0.07f)
    /** Default tinted-glass alpha, e.g. rgba(255,149,0,0.58). */
    const val TINT_ALPHA = 0.58f
}

fun withAlpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24)
