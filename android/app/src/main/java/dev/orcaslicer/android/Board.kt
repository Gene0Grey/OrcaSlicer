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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
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

// Board letters: light-to-regular weight, wide fixed tracking, tabular figures so numbers sit in columns.
private fun caps(size: Int, weight: Int = 400, track: Double = 0.12) =
    TextStyle(fontFamily = Oswald, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = (size * 1.25).sp,
        letterSpacing = track.em, fontFeatureSettings = "tnum")

private val BoardTypography = Typography(
    displaySmall = caps(30, 400, 0.18),
    headlineSmall = caps(24, 400, 0.14),
    titleLarge = caps(21, 500, 0.12),
    titleMedium = caps(17, 400, 0.12),
    titleSmall = caps(15, 500, 0.16),
    labelLarge = caps(15, 500, 0.12),
    labelMedium = caps(13, 400, 0.10),
    labelSmall = caps(11, 400, 0.12),
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

/**
 * Brushed steel: a seamless 256 px tile of horizontal grain, generated once. Each row gets its own
 * brightness and streaks of varying length run along it, the way a brushed sheet catches light.
 */
private val steelTile: ImageBitmap by lazy {
    val n = 256
    val px = IntArray(n * n)
    val rnd = java.util.Random(7)
    for (y in 0 until n) {
        val row = rnd.nextGaussian() * 4.0
        var streak = 0.0
        var left = 0
        for (x in 0 until n) {
            if (left-- <= 0) { streak = rnd.nextGaussian() * 6.0; left = 8 + rnd.nextInt(90) }
            val v = (42 + row + streak + rnd.nextGaussian() * 1.5).coerceIn(28.0, 64.0).toInt()
            px[y * n + x] = (0xFF shl 24) or (v shl 16) or ((v + 3) shl 8) or (v + 7)
        }
    }
    android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}
private val steelBrush by lazy { ShaderBrush(ImageShader(steelTile, TileMode.Repeated, TileMode.Repeated)) }

/** Brushed steel frame: the panel material around flaps, lit top edge and shadowed bottom edge. */
fun Modifier.steel(): Modifier = this.drawBehind {
    drawRect(steelBrush)
    drawLine(Board.frameHi, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
    drawLine(Color(0xFF15171A), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
}

/** A flap face: black cell, lighter upper half, the split line across the middle. */
fun Modifier.flap(border: Color? = null, radius: Dp = 3.dp): Modifier = this
    .drawBehind {
        val mid = size.height / 2
        val gap = 1.dp.toPx()
        drawRect(Board.flap)
        drawRect(Board.flapTop, size = size.copy(height = mid - gap))
        drawRect(Color.Black, Offset(0f, mid - gap), size.copy(height = gap * 2))
        // Hinge pins at both ends of the split.
        val pin = Size(3.dp.toPx(), 5.dp.toPx())
        drawRect(Board.steelMid, Offset(0f, mid - pin.height / 2), pin)
        drawRect(Board.steelMid, Offset(size.width - pin.width, mid - pin.height / 2), pin)
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
             color: Color = Board.letter, maxLines: Int = 1, flipIn: Boolean = false) {
    if (reducedMotion() || text.length > 28) {
        Text(text, modifier, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        return
    }
    Row(modifier) {
        text.forEachIndexed { i, c -> FlapChar(c, i, style, color, flipIn) }
    }
}

@Composable
private fun FlapChar(c: Char, index: Int, style: TextStyle, color: Color, flipIn: Boolean) {
    // flipIn: start blank so the first value cascades in (results after a slice).
    var shown by remember { mutableStateOf(if (flipIn) ' ' else c) }
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
             style: TextStyle = MaterialTheme.typography.labelLarge, border: Color? = null, flipIn: Boolean = false) {
    Box(modifier.flap(border).heightIn(min = 40.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
        FlapText(text, style = style, color = color, flipIn = flipIn)
    }
}

/** ON / OFF on a flap: the board's switch. Teal letters and edge when on. */
@Composable
fun FlapToggle(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, role: Role = Role.Switch, label: String? = null) {
    Box(Modifier.heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = role, onValueChange = onChange)
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
        .flap(if (checked) Board.teal else Board.frameHi).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        FlapText(if (checked) "ON " else "OFF", style = MaterialTheme.typography.labelLarge,
            color = when { !enabled -> Board.steelMid; checked -> Board.tealText; else -> Board.steelLight })
    }
}

/** The primary board key: a filled teal flap with its split, the label, and a divided chevron cell. */
@Composable
fun BoardKey(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fill = if (enabled) Board.teal else Board.frame
    val ink = if (enabled) Board.flap else Board.steelMid
    Row(modifier.heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
        .drawBehind {
            drawRect(fill)
            drawRect(Color.Black.copy(alpha = 0.18f), Offset(0f, size.height / 2), size.copy(height = 1.dp.toPx()))
        }, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = ink, modifier = Modifier.padding(start = 14.dp).size(22.dp))
        Text(label.uppercase(), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge, color = ink)
        Box(Modifier.heightIn(min = 56.dp).drawBehind { drawRect(Color.Black.copy(alpha = 0.25f), size = size.copy(width = 1.dp.toPx())) }
            .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = ink)
        }
    }
}

/** Text entry on a flap: board caps, caps placeholder in steel, optional key cell on the right. */
@Composable
fun BoardField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
               keyboard: KeyboardOptions = KeyboardOptions.Default, onDone: (() -> Unit)? = null, key: ImageVector? = null) {
    var focused by remember { mutableStateOf(false) }
    Row(modifier.heightIn(min = 48.dp).flap(if (focused) Board.teal else Board.frameHi), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder.uppercase(), style = MaterialTheme.typography.labelLarge, color = Board.steelLight, maxLines = 1)
            BasicTextField(value, onChange, singleLine = true, cursorBrush = SolidColor(Board.teal),
                textStyle = MaterialTheme.typography.labelLarge.copy(color = Board.letter),
                keyboardOptions = keyboard.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
        }
        if (key != null) Box(Modifier.heightIn(min = 48.dp).drawBehind { drawRect(Board.frameHi, size = size.copy(width = 1.dp.toPx())) }
            .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Icon(key, null, tint = Board.steelLight) }
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
