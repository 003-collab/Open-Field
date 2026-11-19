package com.openfield.survey.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.openfield.core.config.ColorRole
import com.openfield.core.config.Labels
import com.openfield.core.config.ThemeBase
import com.openfield.core.config.ThemeSpec
import com.openfield.core.gnss.SolutionType

val LocalAppTheme = staticCompositionLocalOf { ThemeSpec.classic }
val LocalLabels = staticCompositionLocalOf { Labels() }
val LocalTouch = staticCompositionLocalOf { 1f }
val LocalLeftHanded = staticCompositionLocalOf { false }

/** "#RRGGBB" or "#AARRGGBB" (Android convention: alpha first). Anything unparsable shows as magenta so mistakes are obvious. */
fun parseColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    Color.Magenta
}

fun ThemeSpec.c(role: String): Color = parseColor(color(role))

fun ThemeSpec.solutionColor(s: SolutionType?): Color = c(
    when (s) {
        SolutionType.RTK_FIXED -> ColorRole.FIXED
        SolutionType.RTK_FLOAT -> ColorRole.FLOAT
        SolutionType.DGPS, SolutionType.PPS -> ColorRole.DIFF
        SolutionType.AUTONOMOUS -> ColorRole.AUTONOMOUS
        else -> ColorRole.NO_FIX
    }
)

/** A label from the user's terminology table (their override, else the default wording). */
@Composable
fun label(key: String): String = LocalLabels.current.get(key)

private fun TextUnit.scaled(s: Float): TextUnit = if (this == TextUnit.Unspecified) this else this * s
private fun TextStyle.scaled(s: Float): TextStyle = copy(fontSize = fontSize.scaled(s), lineHeight = lineHeight.scaled(s))

/** Turns a user-editable [ThemeSpec] into a Material theme. Colours, text size, touch size and corner radius all come from it. */
@Composable
fun OpenFieldTheme(spec: ThemeSpec, labels: Labels, leftHanded: Boolean, content: @Composable () -> Unit) {
    val primary = spec.c(ColorRole.PRIMARY)
    val onPrimary = spec.c(ColorRole.ON_PRIMARY)
    val background = spec.c(ColorRole.BACKGROUND)
    val surface = spec.c(ColorRole.SURFACE)
    val onSurface = spec.c(ColorRole.ON_SURFACE)
    val accent = spec.c(ColorRole.ACCENT)
    val outline = spec.c(ColorRole.OUTLINE)
    val bad = spec.c(ColorRole.BAD)

    val scheme = if (spec.base == ThemeBase.DARK) {
        darkColorScheme(
            primary = primary, onPrimary = onPrimary, secondary = accent, background = background, onBackground = onSurface,
            surface = surface, onSurface = onSurface, surfaceVariant = surface, onSurfaceVariant = onSurface, outline = outline,
            error = bad
        )
    } else {
        lightColorScheme(
            primary = primary, onPrimary = onPrimary, secondary = accent, background = background, onBackground = onSurface,
            surface = surface, onSurface = onSurface, surfaceVariant = surface, onSurfaceVariant = onSurface, outline = outline,
            error = bad
        )
    }

    val s = spec.fontScale.toFloat()
    val base = Typography()
    val typography = Typography(
        headlineMedium = base.headlineMedium.scaled(s), headlineSmall = base.headlineSmall.scaled(s),
        titleLarge = base.titleLarge.scaled(s), titleMedium = base.titleMedium.scaled(s), titleSmall = base.titleSmall.scaled(s),
        bodyLarge = base.bodyLarge.scaled(s), bodyMedium = base.bodyMedium.scaled(s), bodySmall = base.bodySmall.scaled(s),
        labelLarge = base.labelLarge.scaled(s), labelMedium = base.labelMedium.scaled(s), labelSmall = base.labelSmall.scaled(s)
    )
    val r = spec.cornerRadiusDp.dp
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(r / 2), small = RoundedCornerShape(r), medium = RoundedCornerShape(r),
        large = RoundedCornerShape(r), extraLarge = RoundedCornerShape(r)
    )

    CompositionLocalProvider(
        LocalAppTheme provides spec,
        LocalLabels provides labels,
        LocalTouch provides spec.touchScale.toFloat(),
        LocalLeftHanded provides leftHanded
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}
