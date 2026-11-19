package dev.orcaslicer.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

fun plateLabel(state: AppState, i: Int) =
    "PLATE %02d".format(i + 1) + state.plates.getOrNull(i)?.name?.takeIf { it.isNotBlank() }?.let { " · ${it.uppercase()}" }.orEmpty()

/** ‹ PLATE 02/03 › on a flap: arrows step through the plates, the cell opens the plate list. */
@Composable
fun PlateSwitcher(state: AppState, host: PlateHost, onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    val n = state.plates.size
    fun go(i: Int) = scope.launch { if (state.plateCmd("select", "index" to i)) { host.fit(state); host.preview = null; host.scene?.requestPreviewInfo() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (n > 1) IconButton({ go((state.currentPlate - 1 + n) % n) }, Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous plate", tint = Board.letter) }
        Surface(onClick = onOpen, color = Color.Transparent, shape = MaterialTheme.shapes.small) {
            FlapCell(if (n > 1) "PLATE ${state.currentPlate + 1}/$n" else "PLATE 01", color = Board.tealText,
                border = if (state.plates.getOrNull(state.currentPlate)?.locked == true) Board.amber else null)
        }
        if (n > 1) IconButton({ go((state.currentPlate + 1) % n) }, Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next plate", tint = Board.letter) }
    }
}

/** The project's plates (desktop plate list): pick, rename, lock, duplicate, delete, add, and move the picked objects. */
@Composable
fun PlatesDialog(state: AppState, host: PlateHost, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rename by remember { mutableIntStateOf(-1) }
    var confirmDelete by remember { mutableIntStateOf(-1) }
    val picked = state.pickedCount
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Board.ground) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DialogBar("Plates", Icons.Default.Close, onClose)
                state.plates.forEachIndexed { i, p ->
                    val live = i == state.currentPlate
                    Column(Modifier.fillMaxWidth().clickable { scope.launch { if (state.plateCmd("select", "index" to i)) { host.fit(state); onClose() } } }
                        .heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(plateLabel(state, i), style = MaterialTheme.typography.titleMedium, color = if (live) Board.tealText else Board.letter,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(
                                    when (p.objects) { 0 -> "EMPTY"; 1 -> "1 OBJECT"; else -> "${p.objects} OBJECTS" },
                                    "SLICED".takeIf { p.sliced }, "LOCKED".takeIf { p.locked },
                                ).joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = if (p.locked) Board.amber else Board.steelLight)
                            }
                            IconButton({ rename = i }) { Icon(Icons.Default.Edit, "Rename plate", tint = Board.steelLight) }
                            IconButton({ scope.launch { state.plateCmd("lock", "index" to i, "value" to !p.locked) } }) {
                                Icon(if (p.locked) Icons.Default.Lock else Icons.Default.LockOpen, if (p.locked) "Unlock plate" else "Lock plate",
                                    tint = if (p.locked) Board.amber else Board.steelLight)
                            }
                            IconButton({ scope.launch { state.plateCmd("duplicate", "index" to i) } }) { Icon(Icons.Default.ContentCopy, "Duplicate plate", tint = Board.steelLight) }
                            IconButton({ confirmDelete = i }, enabled = state.plates.size > 1) { Icon(Icons.Default.Delete, "Delete plate", tint = Board.steelLight) }
                        }
                        if (picked > 0 && !live) TextButton({ scope.launch { state.plateCmd("move_objects", "to" to i); host.fit(state); onClose() } }) {
                            Text("MOVE ${if (picked == 1) "THE PICKED OBJECT" else "$picked PICKED OBJECTS"} HERE", color = Board.tealText)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { scope.launch { if (state.plateCmd("add")) host.fit(state) } }.heightIn(min = 56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Add, null, tint = Board.tealText)
                    Text("ADD PLATE", style = MaterialTheme.typography.labelLarge, color = Board.tealText)
                }
            }
        }
    }
    if (rename >= 0) TextDialog("Plate name", state.plates.getOrNull(rename)?.name.orEmpty(), { rename = -1 }) { n ->
        val i = rename; scope.launch { state.plateCmd("rename", "index" to i, "name" to n) }
    }
    if (confirmDelete >= 0) androidx.compose.material3.AlertDialog(onDismissRequest = { confirmDelete = -1 }, containerColor = Board.frame,
        title = { Text("DELETE ${plateLabel(state, confirmDelete)}?", style = MaterialTheme.typography.titleMedium) },
        text = { Text("Its objects are deleted with it. Undo brings them back.", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton({ val i = confirmDelete; confirmDelete = -1; scope.launch { state.plateCmd("delete", "index" to i); host.fit(state) } }) { Text("DELETE", color = Board.red) } },
        dismissButton = { TextButton({ confirmDelete = -1 }) { Text("CANCEL", color = Board.steelLight) } })
}
