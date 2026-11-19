package dev.orcaslicer.android

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Any image to the 8-bit greyscale PNG libslic3r's height maps read (at most 1024 px a side). */
fun toGreyPng(src: File, out: File): Boolean {
    var bmp = BitmapFactory.decodeFile(src.path) ?: return false
    val scale = 1024f / maxOf(bmp.width, bmp.height)
    if (scale < 1f) bmp = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
    val w = bmp.width; val h = bmp.height
    val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
    val raw = ByteArrayOutputStream()
    DeflaterOutputStream(raw).use { z ->
        for (y in 0 until h) {
            z.write(0) // filter: none
            for (x in 0 until w) {
                val c = px[y * w + x]
                z.write(((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000)
            }
        }
    }
    DataOutputStream(out.outputStream().buffered()).use { o ->
        o.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            o.writeInt(data.size)
            val t = type.toByteArray()
            o.write(t); o.write(data)
            o.writeInt(CRC32().apply { update(t); update(data) }.value.toInt())
        }
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(w); writeInt(h); write(8); write(0); write(0); write(0); write(0) } }
        chunk("IHDR", ihdr.toByteArray())
        chunk("IDAT", raw.toByteArray())
        chunk("IEND", ByteArray(0))
    }
    return true
}

private val PROJECTIONS = listOf("Triplanar", "Cylindrical", "Spherical")

/** Texture displacement (desktop tool, whole-object version): an image's brightness raises the surface. */
@Composable
fun TextureDialog(state: AppState, image: File, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var depth by remember { mutableStateOf("0.4") }
    var tile by remember { mutableStateOf("12.5") }
    var rotation by remember { mutableStateOf("0") }
    var invert by remember { mutableStateOf(false) }
    var projection by remember { mutableIntStateOf(0) }
    var subdivide by remember { mutableIntStateOf(2) }
    fun num(s: String, d: Float) = s.replace(',', '.').toFloatOrNull() ?: d
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text("TEXTURE · ${image.name.uppercase()}", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(depth, { depth = it }, Modifier.weight(1f), colors = fieldColors, label = { Text("Depth mm") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(tile, { tile = it }, Modifier.weight(1f), colors = fieldColors, label = { Text("Tile mm") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(rotation, { rotation = it }, Modifier.weight(1f), colors = fieldColors, label = { Text("Turn °") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Dropdown(PROJECTIONS[projection].uppercase(), PROJECTIONS.map { it.uppercase() }, Modifier.fillMaxWidth()) { projection = it }
                Dropdown("SUBDIVIDE ×$subdivide", (0..4).map { "SUBDIVIDE ×$it" }, Modifier.fillMaxWidth()) { subdivide = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("INVERT", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Switch(invert, { invert = it }, colors = switchColors)
                }
                Text("FINER MESHES SHOW MORE DETAIL; EACH SUBDIVISION QUADRUPLES THE TRIANGLES.", style = MaterialTheme.typography.labelSmall, color = Board.steelMid)
            }
        },
        confirmButton = { TextButton({
            scope.launch {
                state.busy = "Applying texture…"
                val err = withContext(Dispatchers.IO) {
                    val png = File(image.parentFile, "height.png")
                    if (!toGreyPng(image, png)) "Can't read that image."
                    else OrcaCore.textureDisplace(png.path, num(depth, 0.4f), num(tile, 12.5f), num(rotation, 0f), invert, projection, subdivide)
                }
                state.busy = null
                if (err.isNotEmpty()) state.error = err else state.afterGizmo()
            }
            onClose()
        }) { Text("APPLY", color = Board.tealText) } },
        dismissButton = { TextButton(onClose) { Text("CANCEL", color = Board.steelLight) } })
}
