/*
THESIS: The slicer as a departure board: printer, filament, process, settings and results are rows of
  flaps read at a glance. Refuses the CAD-grey icon-toolbar default of desktop slicers.
OWN-WORLD: flap-black cells with a split line on a brushed-steel frame; Oswald condensed caps in letter
  white; Orca teal = live/active, amber = modified/warning, red = cancelled/error; ruled rows, fixed columns.
STORY: A hobbyist reads the setup board like a departure row, drops the model on the plate, hits SLICE
  and watches time and grams flip in; an expert opens any row for the desktop's full depth.
FIRST VIEWPORT: Prepare: full-bleed black plate; plate header row (PLATE 01 · objects · undo/redo) on top;
  tool rail on the left edge; SLICE PLATE button bottom-right; setup board (3 rows) peeking from the
  bottom; navigation bar below (rail on tablets).
FORM: Split-flap concourse board, dealt challenger, seed 034b7718.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, and DESIGN.md
*/
package dev.orcaslicer.android

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** The board's materials. Orca teal replaces the concourse amber as the live colour (brand commitment). */
object Board {
    val flap = Color(0xFF0B0D0D)        // flap face
    val flapTop = Color(0xFF131616)     // upper flap, a shade lighter
    val ground = Color(0xFF16181B)      // behind the frame
    val frame = Color(0xFF2A2D31)       // brushed steel
    val frameHi = Color(0xFF3A3E43)     // steel edge highlight
    val rule = Color(0xFF23262A)        // ruled lines between rows
    val steelMid = Color(0xFF5B5E63)
    val steelLight = Color(0xFFA7AAAF)  // secondary letters
    val letter = Color(0xFFF2F2F2)      // primary letters
    val teal = Color(0xFF009688)        // Orca brand teal: live / active / primary
    val tealText = Color(0xFF2EC4B2)    // teal letters on black (contrast)
    val amber = Color(0xFFFFB000)       // modified / warning
    val red = Color(0xFFE5484D)         // cancelled / error
}

@OptIn(ExperimentalTextApi::class)
private fun oswald(weight: Int) =
    Font(R.font.oswald, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Oswald = FontFamily(oswald(400), oswald(500), oswald(600))

private fun caps(size: Int, weight: Int = 500, track: Double = 0.06) =
    TextStyle(fontFamily = Oswald, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = (size * 1.25).sp, letterSpacing = track.em)

private val BoardTypography = Typography(
    displaySmall = caps(30, 500, 0.08),
    headlineSmall = caps(24, 500, 0.06),
    titleLarge = caps(21, 500, 0.06),
    titleMedium = caps(17, 500, 0.06),
    titleSmall = caps(15, 600, 0.10),
    labelLarge = caps(15, 500, 0.08),
    labelMedium = caps(13, 400, 0.08),
    labelSmall = caps(11, 400, 0.10),
    // Prose (tooltips, descriptions) stays in the system face for legibility.
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

private val BoardScheme: ColorScheme = darkColorScheme(
    primary = Board.teal, onPrimary = Board.flap,
    primaryContainer = Color(0xFF00413A), onPrimaryContainer = Board.tealText,
    secondary = Board.amber, onSecondary = Board.flap,
    secondaryContainer = Color(0xFF00413A), onSecondaryContainer = Board.tealText,
    tertiary = Board.tealText,
    error = Board.red, onError = Board.flap, errorContainer = Color(0xFF3A1114), onErrorContainer = Color(0xFFFFB4B6),
    background = Board.ground, onBackground = Board.letter,
    surface = Board.ground, onSurface = Board.letter,
    surfaceVariant = Board.frame, onSurfaceVariant = Board.steelLight,
    surfaceContainerLowest = Board.flap, surfaceContainerLow = Color(0xFF1C1E21), surfaceContainer = Board.frame,
    surfaceContainerHigh = Color(0xFF32353A), surfaceContainerHighest = Color(0xFF3A3D42),
    outline = Board.steelMid, outlineVariant = Board.frameHi,
)

// Flaps and plates have barely-rounded corners; nothing on a board is a pill.
private val BoardShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(3.dp), medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp), extraLarge = RoundedCornerShape(10.dp),
)

@Composable
fun BoardTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = BoardScheme, typography = BoardTypography, shapes = BoardShapes, content = content)

/** Brushed steel frame: the panel material around flaps. */
fun Modifier.steel(): Modifier = this.drawBehind {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF2F3236), Board.frame, Color(0xFF26292C))))
    drawLine(Board.frameHi, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
    drawLine(Color(0xFF15171A), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
}

/** A flap face: black cell, lighter upper half, the split line across the middle. */
fun Modifier.flap(border: Color? = null, radius: Dp = 3.dp): Modifier = this
    .drawBehind {
        drawRect(Board.flap)
        drawRect(Board.flapTop, size = size.copy(height = size.height / 2))
        drawLine(Color.Black, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
    }
    .let { if (border != null) it.border(1.dp, border, RoundedCornerShape(radius)) else it }

@Composable
private fun reducedMotion(): Boolean {
    val cr = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * Text that changes like a split-flap board: each changed character flips over, staggered left to
 * right. The first value appears without animation; system "remove animations" turns flips off.
 */
@Composable
fun FlapText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.labelLarge,
             color: Color = Board.letter, maxLines: Int = 1) {
    if (reducedMotion() || text.length > 28) {
        Text(text, modifier, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        return
    }
    Row(modifier) {
        text.forEachIndexed { i, c -> FlapChar(c, i, style, color) }
    }
}

@Composable
private fun FlapChar(c: Char, index: Int, style: TextStyle, color: Color) {
    var shown by remember { mutableStateOf(c) }
    val rot = remember { Animatable(0f) }
    LaunchedEffect(c) {
        if (c == shown) return@LaunchedEffect
        delay(index * 22L)
        rot.animateTo(90f, tween(70))
        shown = c
        rot.snapTo(-90f)
        rot.animateTo(0f, tween(90))
    }
    Text(shown.toString(), style = style, color = color, modifier = Modifier.graphicsLayer { rotationX = rot.value; cameraDistance = 12f * density })
}

/** A value shown on a flap cell, optionally in a status colour. */
@Composable
fun FlapCell(text: String, modifier: Modifier = Modifier, color: Color = Board.letter,
             style: TextStyle = MaterialTheme.typography.labelLarge, border: Color? = null) {
    Box(modifier.flap(border).heightIn(min = 40.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
        FlapText(text, style = style, color = color)
    }
}

/** Ruled section heading in tracked caps. */
@Composable
fun BoardHeading(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).drawBehind {
            drawLine(Board.steelMid, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx())
        }.padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Board.steelLight)
        trailing()
    }
}

/** One board row: ruled underneath, label column + content. */
@Composable
fun BoardRow(label: String, modifier: Modifier = Modifier, labelWidth: Dp = 96.dp, labelColor: Color = Board.steelLight,
             content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).drawBehind {
            drawLine(Board.rule, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx())
        }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label.uppercase(), Modifier.width(labelWidth), style = MaterialTheme.typography.labelMedium, color = labelColor, maxLines = 3)
        content()
    }
}

/** Steel panel surface (sheets, dialogs, bars). */
@Composable
fun SteelPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Surface(modifier, color = Color.Transparent) { Column(Modifier.steel()) { content() } }
