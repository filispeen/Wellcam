package com.filispeen.WinCamClient

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ColorBg = Color(0xFF0E0E12)
private val ColorSurface = Color(0xFF1C1C22)
private val ColorBorder = Color(0xFF2A2A33)
private val ColorMuted = Color(0xFF8E8E99)
private val ColorStatusText = Color(0xFFB4B4BF)
private val ColorAccent = Color(0xFF1D4ED8)
private val ColorStop = Color(0xFFB91C1C)
private val ColorLive = Color(0xFF22C55E)

private val ResolutionOptions = listOf("1280x720", "1920x1080", "2560x1440")
private val FpsOptions = listOf("24 FPS", "30 FPS", "60 FPS")
private val ConnectionOptions = listOf("USB", "Wi-Fi")

private class UnitShape(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val color: Color,
    val path: Path
)

private fun unitPath(d: String): Path = PathParser().parsePathString(d).toPath()

private const val PATH_ELLIPSE =
    "M0 0.5 C0 0.225 0.225 0 0.5 0 C0.775 0 1 0.225 1 0.5 C1 0.775 0.775 1 0.5 1 C0.225 1 0 0.775 0 0.5 Z"

private const val PATH_TRAPEZOID =
    "M1 1 L0 1 L0.17171717 0.15 C0.19191916 0.05 0.22222221 0 0.25757578 0 L0.7424243 0 " +
        "C0.7777779 0 0.80808085 0.05 0.82828295 0.15 L1 1 Z"

private const val PATH_SMALL_RECT =
    "M1 1 L0 1 L0 0.4 C0 0.16666667 0.083333336 0 0.2 0 L0.8 0 C0.9166667 0 1 0.16666667 1 0.4 L1 1 Z"

private const val PATH_BODY =
    "M0.9 1 L0.1 1 C0.045 1 0 0.94375 0 0.875 L0 0.125 C0 0.05625 0.045 0 0.1 0 L0.9 0 " +
        "C0.955 0 1 0.05625 1 0.125 L1 0.875 C1 0.94375 0.955 1 0.9 1 Z"

private const val PATH_HIGHLIGHT =
    "M0.96702766 0.5082329 C0.85015124 0.18481216 0.67483675 0 0.49952227 0 " +
        "C0.3242078 0 0.14889315 0.18481171 0.0320169 0.5082329 " +
        "C-0.016681587 0.6237403 -0.0069418526 0.808552 0.041756634 0.92405945 " +
        "C0.090455115 1.0395669 0.16837262 1.0164653 0.2170711 0.9009579 " +
        "C0.36316657 0.5082324 0.6358782 0.5082324 0.78197366 0.9009579 " +
        "C0.81119263 0.97026217 0.84041166 0.99336374 0.8793706 0.99336374 " +
        "C0.9085896 0.99336374 0.93780863 0.97026217 0.967028 0.92405945 " +
        "C1.0059866 0.8316536 1.0157261 0.6237403 0.96702766 0.5082329 Z"

private const val PATH_CHEVRON = "M0.5 0 L0 0.6 L0 1 L0.5 0.4 L1 1 L1 0.6 Z"

@Composable
private fun CameraIcon(modifier: Modifier) {
    val shapes = remember {
        listOf(
            UnitShape(53.8f, 17f, 56.1f, 17f, Color(0xFF512DA8), unitPath(PATH_TRAPEZOID)),
            UnitShape(22.7f, 22.7f, 17f, 8.5f, Color(0xFF8668C5), unitPath(PATH_SMALL_RECT)),
            UnitShape(11.3f, 28.3f, 113.3f, 90.7f, Color(0xFF5E35B1), unitPath(PATH_BODY)),
            UnitShape(48.2f, 39.7f, 68f, 68f, Color(0xFF512DA8), unitPath(PATH_ELLIPSE)),
            UnitShape(56.7f, 48.2f, 51f, 51f, Color(0xFFB388FF), unitPath(PATH_ELLIPSE)),
            UnitShape(68.2f, 58.9f, 29.1f, 12.3f, Color(0xFFC7A7FF), unitPath(PATH_HIGHLIGHT)),
            UnitShape(25.5f, 34f, 11.3f, 8.5f, Color(0xFF8668C5), unitPath(PATH_ELLIPSE))
        )
    }
    Canvas(modifier) {
        val f = size.width / 136f
        withTransform({ scale(f, f, Offset.Zero) }) {
            shapes.forEach { sh ->
                withTransform({
                    translate(sh.x, sh.y)
                    scale(sh.w, sh.h, Offset.Zero)
                }) {
                    drawPath(sh.path, sh.color)
                }
            }
        }
    }
}

@Composable
private fun DoubleChevron(modifier: Modifier) {
    val path = remember { unitPath(PATH_CHEVRON) }
    Canvas(modifier) {
        val f = size.width / 68f
        withTransform({ scale(f, f, Offset.Zero) }) {
            listOf(0f, 18.1f).forEach { dy ->
                withTransform({
                    translate(8.5f, 11.3f + dy)
                    scale(51f, 28.3f, Offset.Zero)
                }) {
                    drawPath(path, ColorMuted)
                }
            }
        }
    }
}

@Composable
private fun PreviewPanel(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(Color(0xFF22222B), Color(0xFF121217))),
                shape
            )
            .border(1.dp, ColorBorder, shape),
        contentAlignment = Alignment.Center
    ) {
        val icon = (minOf(maxWidth, maxHeight) * 0.3f).coerceIn(48.dp, 136.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CameraIcon(Modifier.size(icon))
            Text(
                text = "Camera preview",
                color = ColorMuted,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun StatusChip(streaming: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ColorSurface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (streaming) ColorLive else ColorMuted)
        )
        Text(
            text = if (streaming) "Streaming" else "Disconnected",
            color = ColorStatusText,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun Header(streaming: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "WCam",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        StatusChip(streaming)
    }
}

@Composable
private fun LabeledDropdown(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val density = LocalDensity.current
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(0.dp) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, color = ColorMuted, fontSize = 13.sp)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .onSizeChanged { anchorWidth = with(density) { it.width.toDp() } }
                    .clip(shape)
                    .background(ColorSurface, shape)
                    .border(1.dp, ColorBorder, shape)
                    .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value,
                    color = if (enabled) Color.White else ColorMuted,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                DoubleChevron(Modifier.size(28.dp))
            }
            DropdownMenu(
                expanded = expanded && enabled,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .width(anchorWidth)
                    .background(ColorSurface)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                color = if (option == value) ColorLive else Color.White,
                                fontSize = 16.sp
                            )
                        },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun Controls(
    resolution: String,
    onResolution: (String) -> Unit,
    fps: String,
    onFps: (String) -> Unit,
    connection: String,
    onConnection: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabeledDropdown("Resolution", resolution, ResolutionOptions, enabled, onResolution)
        LabeledDropdown("Frame rate (FPS)", fps, FpsOptions, enabled, onFps)
        LabeledDropdown("Connection method", connection, ConnectionOptions, enabled, onConnection)
    }
}

@Composable
private fun StartButton(streaming: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (streaming) ColorStop else ColorAccent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (streaming) "Stop streaming" else "Start streaming",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun CameraClientLandscape(modifier: Modifier = Modifier) {
    var resolution by rememberSaveable { mutableStateOf("1920x1080") }
    var fps by rememberSaveable { mutableStateOf("60 FPS") }
    var connection by rememberSaveable { mutableStateOf("USB") }
    var streaming by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBg)
            .safeDrawingPadding()
    ) {
        if (maxWidth > maxHeight) {
            val panelWidth = (maxWidth * 0.29f).coerceIn(260.dp, 420.dp)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PreviewPanel(Modifier.weight(1f).fillMaxHeight())
                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Header(streaming)
                    Controls(
                        resolution = resolution,
                        onResolution = { resolution = it },
                        fps = fps,
                        onFps = { fps = it },
                        connection = connection,
                        onConnection = { connection = it },
                        enabled = !streaming,
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    )
                    StartButton(streaming) { streaming = !streaming }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Header(streaming)
                PreviewPanel(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                )
                Controls(
                    resolution = resolution,
                    onResolution = { resolution = it },
                    fps = fps,
                    onFps = { fps = it },
                    connection = connection,
                    onConnection = { connection = it },
                    enabled = !streaming
                )
                StartButton(streaming) { streaming = !streaming }
            }
        }
    }
}

@Preview(widthDp = 891, heightDp = 411, showBackground = true)
@Composable
private fun CameraClientLandscapePreview() {
    CameraClientLandscape()
}

@Preview(widthDp = 411, heightDp = 891, showBackground = true)
@Composable
private fun CameraClientPortraitPreview() {
    CameraClientLandscape()
}
