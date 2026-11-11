package ua.dash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class Palette(
    val bg: Color,
    val card: Color,
    val card2: Color,
    val text: Color,
    val muted: Color,
    val headerBg: Color,
    val headerText: Color,
    val headerMuted: Color,
    val on: Color,
    val off: Color,
    val maybe: Color,
    val track: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val alertBg: Color,
    val claude: Color,
    val codex: Color,
)

val DarkPalette = Palette(
    bg = Color(0xFF0E1116),
    card = Color(0xFF181D26),
    card2 = Color(0xFF212835),
    text = Color(0xFFF2F4F8),
    muted = Color(0xFF8B95A7),
    headerBg = Color(0xFF1E2531),
    headerText = Color(0xFFFFFFFF),
    headerMuted = Color(0xFF9AA4B5),
    on = Color(0xFFF5C542),
    off = Color(0xFF3A4458),
    maybe = Color(0xFF8E7A3F),
    track = Color(0xFF2B3342),
    good = Color(0xFF3DD68C),
    warn = Color(0xFFF5A524),
    bad = Color(0xFFFF5C5C),
    alertBg = Color(0xFFD93A3A),
    claude = Color(0xFFE07A5F),
    codex = Color(0xFF4FB7A0),
)

val LightPalette = Palette(
    bg = Color(0xFFE9ECF1),
    card = Color(0xFFFFFFFF),
    card2 = Color(0xFFF2F4F7),
    text = Color(0xFF11151C),
    muted = Color(0xFF6B7280),
    headerBg = Color(0xFF1B2230),
    headerText = Color(0xFFFFFFFF),
    headerMuted = Color(0xFFAEB6C4),
    on = Color(0xFFFFCB3D),
    off = Color(0xFF1B2433),
    maybe = Color(0xFFC9CED8),
    track = Color(0xFFE3E6EC),
    good = Color(0xFF1E9E5A),
    warn = Color(0xFFD9820B),
    bad = Color(0xFFD63B3B),
    alertBg = Color(0xFFD93A3A),
    claude = Color(0xFFC9613F),
    codex = Color(0xFF14876F),
)

val LocalPalette = compositionLocalOf { DarkPalette }
/** Масштаб: интерфейс рассчитан на 960×600 dp и растягивается под экран */
val LocalScale = compositionLocalOf { 1f }

@Composable
@ReadOnlyComposable
fun d(v: Number): Dp = (v.toFloat() * LocalScale.current).dp

@Composable
@ReadOnlyComposable
fun t(v: Number): TextUnit = (v.toFloat() * LocalScale.current).sp
