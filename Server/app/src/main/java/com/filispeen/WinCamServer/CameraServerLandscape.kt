package com.filispeen.WinCamServer

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import com.filispeen.WinCamServer.encoder.EncoderSupport
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.filispeen.WinCamServer.camera.CameraBackend
import com.filispeen.WinCamServer.camera.CameraBackends
import com.filispeen.WinCamServer.camera.CameraConfig
import com.filispeen.WinCamServer.camera.defaultFps
import com.filispeen.WinCamServer.camera.defaultSize
import com.filispeen.WinCamServer.camera.groupByAspect
import com.filispeen.WinCamServer.camera.CameraPreview
import com.filispeen.WinCamServer.transport.LocalAddresses
import com.filispeen.WinCamServer.transport.ServerState
import kotlinx.coroutines.delay

private val ColorBg = Color(0xFF0E0E12)
private val ColorSurface = Color(0xFF1C1C22)
private val ColorBorder = Color(0xFF2A2A33)
private val ColorMuted = Color(0xFF8E8E99)
private val ColorStatusText = Color(0xFFB4B4BF)
private val ColorAccent = Color(0xFF1D4ED8)
private val ColorStop = Color(0xFFB91C1C)
private val ColorLive = Color(0xFF22C55E)

private val FallbackSizes = listOf(Size(1280, 720), Size(1920, 1080), Size(2560, 1440))
private val FallbackFps = listOf(24, 30, 60)
private const val AdbConnection = "USB (ADB)"
private const val WifiConnection = "Wi-Fi"
private const val AoaConnection = "USB (AOA)"
private val ConnectionOptions = listOf(AdbConnection, AoaConnection, WifiConnection)

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
private fun PreviewPanel(
    backend: CameraBackend?,
    hasPermission: Boolean,
    error: String?,
    config: CameraConfig,
    zoom: Float,
    onMaxZoom: (Float) -> Unit,
    onPlaceholderClick: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
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
        if (hasPermission && error == null && backend != null) {
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val ratio = if (landscape) {
                config.width.toFloat() / config.height
            } else {
                config.height.toFloat() / config.width
            }
            Box(Modifier.aspectRatio(ratio)) {
                CameraPreview(
                    backend = backend,
                    config = config,
                    zoom = zoom,
                    onMaxZoom = onMaxZoom,
                    modifier = Modifier.fillMaxSize(),
                    onError = onError
                )
            }
        } else {
            val icon = (minOf(maxWidth, maxHeight) * 0.3f).coerceIn(48.dp, 136.dp)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onPlaceholderClick)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                CameraIcon(Modifier.size(icon))
                Text(
                    text = error?.let { "$it. Tap to retry" }
                        ?: "Camera permission required. Tap to grant",
                    color = ColorMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private val ZoomBarWidth = 56.dp
private val ZoomBarGap = 12.dp

@Composable
private fun ZoomBar(
    zoom: Float,
    maxZoom: Float,
    enabled: Boolean,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    val upper = maxOf(maxZoom, 1.01f)
    Column(
        modifier = modifier
            .clip(shape)
            .background(ColorSurface, shape)
            .border(1.dp, ColorBorder, shape)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "%.1fx".format(zoom),
            color = if (enabled) Color.White else ColorMuted,
            fontSize = 12.sp,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = zoom.coerceIn(1f, upper),
                onValueChange = onZoom,
                valueRange = 1f..upper,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = ColorAccent,
                    inactiveTrackColor = ColorBorder,
                    disabledThumbColor = ColorMuted,
                    disabledActiveTrackColor = ColorBorder,
                    disabledInactiveTrackColor = ColorBorder
                ),
                modifier = Modifier
                    .graphicsLayer {
                        rotationZ = 270f
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            Constraints(
                                minWidth = constraints.minHeight,
                                maxWidth = constraints.maxHeight,
                                minHeight = constraints.minWidth,
                                maxHeight = constraints.maxWidth
                            )
                        )
                        layout(placeable.height, placeable.width) {
                            placeable.place(-placeable.width, 0)
                        }
                    }
            )
        }
    }
}

@Composable
private fun PreviewSection(
    backend: CameraBackend?,
    hasPermission: Boolean,
    error: String?,
    config: CameraConfig,
    zoom: Float,
    maxZoom: Float,
    onZoom: (Float) -> Unit,
    onMaxZoom: (Float) -> Unit,
    onPlaceholderClick: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ZoomBarGap)) {
        ZoomBar(
            zoom = zoom,
            maxZoom = maxZoom,
            enabled = backend != null && hasPermission && error == null && maxZoom > 1.01f,
            onZoom = onZoom,
            modifier = Modifier
                .width(ZoomBarWidth)
                .fillMaxHeight()
        )
        PreviewPanel(
            backend = backend,
            hasPermission = hasPermission,
            error = error,
            config = config,
            zoom = zoom,
            onMaxZoom = onMaxZoom,
            onPlaceholderClick = onPlaceholderClick,
            onError = onError,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun StatusChip(label: String, streaming: Boolean) {
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
            text = label,
            color = ColorStatusText,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun Header(label: String, streaming: Boolean) {
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
        StatusChip(label, streaming)
    }
}

@Composable
private fun LabeledDropdown(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    groups: List<Pair<String, List<String>>>? = null
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
                val sections = groups ?: listOf("" to options)
                sections.forEach { (title, items) ->
                    if (title.isNotEmpty()) {
                        Text(
                            text = title,
                            color = ColorMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                    items.forEach { option ->
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
}

@Composable
private fun AdvancedButton(summary: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ColorSurface, shape)
            .border(1.dp, ColorBorder, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Advanced",
            color = if (enabled) Color.White else ColorMuted,
            fontSize = 16.sp
        )
        Text(text = summary, color = ColorMuted, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun BitrateField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' }) },
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            disabledTextColor = ColorMuted,
            focusedBorderColor = ColorAccent,
            unfocusedBorderColor = ColorBorder,
            disabledBorderColor = ColorBorder,
            focusedLabelColor = ColorMuted,
            unfocusedLabelColor = ColorMuted,
            disabledLabelColor = ColorMuted,
            cursorColor = Color.White
        )
    )
}

@Composable
private fun AdvancedDialog(
    enabled: Boolean,
    codecs: List<String>,
    codec: String,
    onCodec: (String) -> Unit,
    constant: Boolean,
    onConstant: (Boolean) -> Unit,
    error: String?,
    auto: Boolean,
    onAuto: (Boolean) -> Unit,
    bitrate: String,
    onBitrate: (String) -> Unit,
    maxBitrate: String,
    onMaxBitrate: (String) -> Unit,
    minBitrate: String,
    onMinBitrate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ColorSurface,
        title = { Text("Advanced", color = Color.White) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LabeledDropdown("Codec", codec, codecs, enabled && codecs.isNotEmpty(), onCodec)
                LabeledDropdown(
                    "Bitrate mode",
                    if (constant) "CBR" else "VBR",
                    listOf("CBR", "VBR"),
                    enabled,
                    onSelect = { onConstant(it == "CBR") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto bitrate (high quality)", color = Color.White, fontSize = 15.sp)
                    Switch(checked = auto, onCheckedChange = onAuto, enabled = enabled)
                }
                BitrateField("Bitrate (Mbps)", bitrate, enabled && !auto, onBitrate)
                BitrateField("Max bitrate (Mbps)", maxBitrate, enabled && !auto, onMaxBitrate)
                BitrateField("Min bitrate (Mbps)", minBitrate, enabled && !auto, onMinBitrate)
                if (error != null) {
                    Text(error, color = ColorStop, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = ColorAccent) }
        }
    )
}

@Composable
private fun Controls(
    camera: String,
    cameraOptions: List<String>,
    onCamera: (String) -> Unit,
    resolution: String,
    onResolution: (String) -> Unit,
    fps: String,
    onFps: (String) -> Unit,
    resolutionGroups: List<Pair<String, List<String>>>,
    fpsOptions: List<String>,
    connection: String,
    onConnection: (String) -> Unit,
    address: String,
    advancedSummary: String,
    onAdvanced: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabeledDropdown("Camera", camera, cameraOptions, enabled && cameraOptions.isNotEmpty(), onCamera)
        LabeledDropdown(
            "Resolution",
            resolution,
            resolutionGroups.flatMap { it.second },
            enabled,
            onResolution,
            resolutionGroups
        )
        LabeledDropdown("Frame rate (FPS)", fps, fpsOptions, enabled, onFps)
        LabeledDropdown("Connection method", connection, ConnectionOptions, enabled, onConnection)
        AdvancedButton(advancedSummary, enabled, onAdvanced)
        Text(text = address, color = ColorMuted, fontSize = 13.sp)
    }
}

@Composable
private fun StreamMessage(message: String?) {
    if (message != null) {
        Text(message, color = ColorStop, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
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
            text = if (streaming) "Stop server" else "Start server",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun CameraServerLandscape(viewModel: ServerViewModel, modifier: Modifier = Modifier) {
    var savedResolution by rememberSaveable { mutableStateOf<String?>(null) }
    var savedFps by rememberSaveable { mutableStateOf<String?>(null) }
    var connection by rememberSaveable { mutableStateOf(AdbConnection) }
    val serverState = viewModel.serverState
    val serverInfo = viewModel.serverInfo
    val clientDropped = viewModel.clientDropped
    val encoderSurface = viewModel.encoderSurface
    var streamError by viewModel::streamError
    val failStream: (String) -> Unit = viewModel::failStream
    val streaming = serverState == ServerState.Listening || serverState == ServerState.Connected
    var localAddress by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(connection) {
        viewModel.selectAoa(connection == AoaConnection)
        if (connection == AdbConnection || connection == AoaConnection) {
            localAddress = null
        } else {
            while (true) {
                localAddress = LocalAddresses.find()
                delay(1000)
            }
        }
    }
    val address = when {
        serverState == ServerState.Error -> serverInfo ?: "Error"
        connection == AdbConnection -> "localhost:$ServerPort (adb forward)"
        connection == AoaConnection -> "USB cable (accessory mode)"
        else -> localAddress?.let { "$it:$ServerPort" } ?: "No Wi-Fi address"
    }
    val statusLabel = when (serverState) {
        ServerState.Stopped -> "Stopped"
        ServerState.Listening -> when {
            clientDropped -> "Client disconnected"
            connection == AoaConnection -> "Waiting for USB host"
            else -> "Listening"
        }
        ServerState.Connected -> "Connected: ${serverInfo.orEmpty()}"
        ServerState.Error -> "Error"
    }

    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    var hasPermission by remember {
        mutableStateOf(
            !inspection && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) {
        if (!inspection && !hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    val onPlaceholderClick: () -> Unit = {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            cameraError = null
        }
    }
    val onCameraError: (String) -> Unit = { message ->
        cameraError = message
        if (viewModel.isServerRunning()) failStream("Camera: $message")
    }

    val backend = remember(context, inspection) {
        if (inspection) null else CameraBackends.create(context)
    }
    val cameraOptions = remember(backend) { backend?.cameras().orEmpty() }
    val cameraLabels = remember(cameraOptions) { cameraOptions.map { it.label } }
    var savedCameraId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeCamera = cameraOptions.firstOrNull { it.id == savedCameraId }
        ?: cameraOptions.firstOrNull()
    var zoom by viewModel::zoom
    var maxZoom by viewModel::maxZoom
    val onCamera: (String) -> Unit = { label ->
        cameraOptions.firstOrNull { it.label == label }?.let {
            savedCameraId = it.id
            zoom = 1f
        }
    }
    val cameraLabel = activeCamera?.label ?: "No camera"
    val allSizes = remember(backend, activeCamera?.id) {
        backend?.sizes(activeCamera?.id).orEmpty().ifEmpty { FallbackSizes }
    }
    val resolutionGroups = remember(allSizes) {
        groupByAspect(allSizes).map { group ->
            group.ratio to group.sizes.map { "${it.width}x${it.height}" }
        }
    }
    val resolution = savedResolution?.takeIf { saved -> resolutionGroups.any { saved in it.second } }
        ?: defaultSize(allSizes)?.let { "${it.width}x${it.height}" }
        ?: "1280x720"
    val selectedSize = allSizes.firstOrNull { "${it.width}x${it.height}" == resolution }
    val fpsValues = remember(backend, activeCamera?.id, resolution) {
        backend?.fpsOptions(activeCamera?.id, selectedSize).orEmpty().ifEmpty { FallbackFps }
    }
    val fpsLabels = remember(fpsValues) { fpsValues.map { "$it FPS" } }
    val fps = savedFps?.takeIf { it in fpsLabels }
        ?: defaultFps(fpsValues)?.let { "$it FPS" }
        ?: "30 FPS"
    val previewConfig = CameraConfig(
        selectedSize?.width ?: 1280,
        selectedSize?.height ?: 720,
        activeCamera?.id,
        fps.removeSuffix(" FPS").toIntOrNull() ?: 30
    )
    val codecs = remember { EncoderSupport.supportedCodecs() }
    var savedCodec by rememberSaveable { mutableStateOf<String?>(null) }
    val codec = codecs.firstOrNull { it.label == savedCodec } ?: codecs.firstOrNull()
    var autoBitrate by rememberSaveable { mutableStateOf(true) }
    var manualBitrate by rememberSaveable { mutableStateOf("") }
    var manualMax by rememberSaveable { mutableStateOf("") }
    var manualMin by rememberSaveable { mutableStateOf("") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val autoSettings = remember(codec, previewConfig) {
        codec?.let {
            EncoderSupport.autoBitrate(it, previewConfig.width, previewConfig.height, previewConfig.fps)
        }
    }
    var constantBitrate by rememberSaveable { mutableStateOf(true) }
    val manualSettings = remember(manualBitrate, manualMin, manualMax) {
        EncoderSupport.manualSettings(manualBitrate, manualMin, manualMax)
    }
    val settings = if (autoBitrate) autoSettings else manualSettings
    val bitrateError = when {
        autoBitrate || codec == null -> null
        manualSettings == null -> "Enter valid numbers in Mbps"
        else -> EncoderSupport.validate(codec, manualSettings)
    }
    val toggleServer: () -> Unit = {
        val problem = when {
            streaming -> null
            !hasPermission -> "Camera permission is required"
            backend == null || cameraOptions.isEmpty() -> "No camera available"
            backend.api != "Camera2" -> "Streaming needs Camera2, not supported on this device"
            codec == null -> "No video encoder available"
            bitrateError != null -> "Bitrate: $bitrateError"
            else -> null
        }
        if (streaming) {
            streamError = null
            viewModel.stopServer()
        } else if (problem != null) {
            streamError = problem
            if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            streamError = null
            cameraError = null
            viewModel.startServer()
        }
    }
    val rotation = remember(backend, activeCamera?.id) {
        if (backend == null) 0 else CameraBackends.sensorOrientation(context, activeCamera?.id)
    }
    val encoderSpec = if (streaming && codec != null && settings != null && backend?.api == "Camera2") {
        EncoderSpec(
            codec,
            previewConfig.width,
            previewConfig.height,
            previewConfig.fps,
            rotation,
            settings,
            constantBitrate
        )
    } else {
        null
    }
    LaunchedEffect(encoderSpec) { viewModel.syncEncoder(encoderSpec) }
    val cameraConfig = previewConfig.copy(encoderSurface = encoderSurface)
    val modeLabel = if (constantBitrate) "CBR" else "VBR"
    val advancedSummary = (codec?.label ?: "No encoder") + ", " + modeLabel + ", " +
        if (autoBitrate) "auto" else if (bitrateError != null) "invalid bitrate" else "$manualBitrate Mbps"
    if (showAdvanced) {
        AdvancedDialog(
            enabled = !streaming,
            codecs = codecs.map { it.label },
            codec = codec?.label ?: "No encoder",
            onCodec = { savedCodec = it },
            constant = constantBitrate,
            onConstant = { constantBitrate = it },
            error = bitrateError,
            auto = autoBitrate,
            onAuto = { on ->
                if (!on && autoSettings != null) {
                    manualBitrate = EncoderSupport.formatMbps(autoSettings.bitrate)
                    manualMax = EncoderSupport.formatMbps(autoSettings.max)
                    manualMin = EncoderSupport.formatMbps(autoSettings.min)
                }
                autoBitrate = on
            },
            bitrate = if (autoBitrate) autoSettings?.let { EncoderSupport.formatMbps(it.bitrate) }.orEmpty() else manualBitrate,
            onBitrate = { manualBitrate = it },
            maxBitrate = if (autoBitrate) autoSettings?.let { EncoderSupport.formatMbps(it.max) }.orEmpty() else manualMax,
            onMaxBitrate = { manualMax = it },
            minBitrate = if (autoBitrate) autoSettings?.let { EncoderSupport.formatMbps(it.min) }.orEmpty() else manualMin,
            onMinBitrate = { manualMin = it },
            onDismiss = { showAdvanced = false }
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBg)
            .safeDrawingPadding()
    ) {
        val screenWidth = maxWidth
        if (maxWidth > maxHeight) {
            val panelWidth = (maxWidth * 0.29f).coerceIn(260.dp, 420.dp)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PreviewSection(
                    backend = backend,
                    hasPermission = hasPermission,
                    error = cameraError,
                    config = cameraConfig,
                    zoom = zoom,
                    maxZoom = maxZoom,
                    onZoom = { zoom = it },
                    onMaxZoom = { maxZoom = it },
                    onPlaceholderClick = onPlaceholderClick,
                    onError = onCameraError,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Header(statusLabel, streaming)
                    Controls(
                        camera = cameraLabel,
                        cameraOptions = cameraLabels,
                        onCamera = onCamera,
                        resolution = resolution,
                        onResolution = { savedResolution = it },
                        fps = fps,
                        onFps = { savedFps = it },
                        resolutionGroups = resolutionGroups,
                        fpsOptions = fpsLabels,
                        connection = connection,
                        onConnection = { connection = it },
                        address = address,
                        advancedSummary = advancedSummary,
                        onAdvanced = { showAdvanced = true },
                        enabled = !streaming,
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    )
                    StreamMessage(streamError)
                    StartButton(streaming, toggleServer)
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
                Header(statusLabel, streaming)
                PreviewSection(
                    backend = backend,
                    hasPermission = hasPermission,
                    error = cameraError,
                    config = cameraConfig,
                    zoom = zoom,
                    maxZoom = maxZoom,
                    onZoom = { zoom = it },
                    onMaxZoom = { maxZoom = it },
                    onPlaceholderClick = onPlaceholderClick,
                    onError = onCameraError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((screenWidth - 32.dp - ZoomBarWidth - ZoomBarGap) * 9f / 16f)
                )
                Controls(
                    camera = cameraLabel,
                    cameraOptions = cameraLabels,
                    onCamera = onCamera,
                    resolution = resolution,
                    onResolution = { savedResolution = it },
                    fps = fps,
                    onFps = { savedFps = it },
                    resolutionGroups = resolutionGroups,
                    fpsOptions = fpsLabels,
                    connection = connection,
                    onConnection = { connection = it },
                    address = address,
                    advancedSummary = advancedSummary,
                    onAdvanced = { showAdvanced = true },
                    enabled = !streaming
                )
                StreamMessage(streamError)
                StartButton(streaming, toggleServer)
            }
        }
    }
}

@Preview(widthDp = 891, heightDp = 411, showBackground = true)
@Composable
private fun CameraServerLandscapePreview() {
    val app = LocalContext.current.applicationContext as Application
    CameraServerLandscape(remember { ServerViewModel(app) })
}

@Preview(widthDp = 411, heightDp = 891, showBackground = true)
@Composable
private fun CameraServerPortraitPreview() {
    val app = LocalContext.current.applicationContext as Application
    CameraServerLandscape(remember { ServerViewModel(app) })
}
