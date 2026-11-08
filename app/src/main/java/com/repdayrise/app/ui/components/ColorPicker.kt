package com.repdayrise.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.mutableStateOf

/** Rainbow sweep used for the "custom colour" swatch. */
val RainbowSweep: Brush = Brush.sweepGradient(
    listOf(Color(0xFFFF4D4D), Color(0xFFFFB020), Color(0xFFF2D14B), Color(0xFF4CC28E), Color(0xFF4FA3E3), Color(0xFF7C6CF0), Color(0xFFF06AA8), Color(0xFFFF4D4D)),
)

/** HSV colour picker dialog: a saturation/brightness square, a hue bar, and a live preview. */
@Composable
fun ColorPickerDialog(initial: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val hsv = remember(initial) {
        val a = FloatArray(3)
        android.graphics.Color.colorToHSV(initial.toArgb(), a)
        a
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1].coerceAtLeast(0.05f)) }
    var value by remember { mutableFloatStateOf(hsv[2].coerceAtLeast(0.15f)) }
    val color = Color.hsv(hue, sat, value)
    val hex = "#%06X".format(color.toArgb() and 0xFFFFFF)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(accentGradient(color)))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Custom colour", style = MaterialTheme.typography.titleMedium)
                        Text(hex, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(18.dp))

                // Saturation (x) / brightness (y)
                var boxSize by remember { mutableStateOf(Size.Zero) }
                fun update(p: Offset) {
                    if (boxSize.width <= 0f) return
                    sat = (p.x / boxSize.width).coerceIn(0f, 1f)
                    value = 1f - (p.y / boxSize.height).coerceIn(0f, 1f)
                }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .pointerInput(Unit) { detectTapGestures { update(it) } }
                        .pointerInput(Unit) { detectDragGestures(onDragStart = { update(it) }) { change, _ -> update(change.position) } },
                ) {
                    boxSize = size
                    drawRect(Color.hsv(hue, 1f, 1f))
                    drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val c = Offset(sat * size.width, (1f - value) * size.height)
                    drawCircle(Color.White, 11.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                    drawCircle(color, 8.dp.toPx(), c)
                }
                Spacer(Modifier.height(16.dp))

                // Hue
                var barWidth by remember { mutableStateOf(0f) }
                fun updateHue(x: Float) { if (barWidth > 0f) hue = (x / barWidth).coerceIn(0f, 1f) * 360f }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .pointerInput(Unit) { detectTapGestures { updateHue(it.x) } }
                        .pointerInput(Unit) { detectDragGestures(onDragStart = { updateHue(it.x) }) { change, _ -> updateHue(change.position.x) } },
                ) {
                    barWidth = size.width
                    drawRect(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) }))
                    val x = hue / 360f * size.width
                    drawCircle(Color.White, size.height / 2 - 1.dp.toPx(), Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
                    drawCircle(Color.hsv(hue, 1f, 1f), size.height / 2 - 4.dp.toPx(), Offset(x, size.height / 2))
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onPick(color) }, colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = if (value > 0.7f && sat < 0.5f) Color.Black else Color.White)) {
                        Text("Use colour")
                    }
                }
            }
        }
    }
}

/** The swatch that opens the picker: a rainbow ring with a plus, or the chosen custom colour once set. */
@Composable
fun CustomColorSwatch(custom: Color?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(38.dp)
            .clip(CircleShape)
            .then(if (custom != null) Modifier.background(custom) else Modifier.background(RainbowSweep))
            .then(if (custom != null) Modifier.border(2.dp, RainbowSweep, CircleShape) else Modifier)
            .pointerInput(Unit) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        if (custom == null) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                Text("+", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        } else if (selected) {
            Text("✓", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}
