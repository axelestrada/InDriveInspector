package com.axelestrada.indriveinspector.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.axelestrada.indriveinspector.MainActivity
import com.axelestrada.indriveinspector.capture.ScreenCaptureService
import com.axelestrada.indriveinspector.core.InspectorRepository
import com.axelestrada.indriveinspector.core.Notifications
import com.axelestrada.indriveinspector.core.ProfitSettings
import com.axelestrada.indriveinspector.ocr.OcrWatchService
import com.axelestrada.indriveinspector.overlay.OverlayService
import com.axelestrada.indriveinspector.ui.components.AuroraBackground
import com.axelestrada.indriveinspector.ui.components.HoldToStartButton
import com.axelestrada.indriveinspector.ui.theme.Ink
import com.axelestrada.indriveinspector.ui.theme.Lime
import com.axelestrada.indriveinspector.ui.theme.Outline
import com.axelestrada.indriveinspector.ui.theme.StatusGreen
import com.axelestrada.indriveinspector.ui.theme.StatusRed
import com.axelestrada.indriveinspector.ui.theme.StatusYellow
import com.axelestrada.indriveinspector.ui.theme.Surface1
import com.axelestrada.indriveinspector.ui.theme.Surface2
import com.axelestrada.indriveinspector.ui.theme.TextHigh
import com.axelestrada.indriveinspector.ui.theme.TextLow
import com.axelestrada.indriveinspector.ui.theme.TextMid
import java.util.Locale

/**
 * App minimalista: un botón y una página de ajustes.
 * Los datos de la oferta viven únicamente en el widget flotante.
 */
@Composable
fun MainScreen() {

    val context = LocalContext.current
    val settings by InspectorRepository.settings.collectAsState()
    val servicesActive by InspectorRepository.servicesActive.collectAsState()

    var message by remember { mutableStateOf<String?>(null) }
    var resetSignal by remember { mutableIntStateOf(0) }
    var continueNotificationStart by remember { mutableStateOf(false) }
    val navController = rememberNavController()
    val mainActivity = context as? MainActivity

    LaunchedEffect(Unit) {
        InspectorRepository.loadSettings(context)
        if (!servicesActive) Notifications.showStopped(context)
    }

    // --- Permiso de captura de pantalla (MediaProjection) ---
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    ScreenCaptureService.startIntent(context, result.resultCode, data)
                )
                // Sólo con el permiso concedido se activan el resto de servicios
                startServices(context)
                message = "Listo. El widget aparece sobre inDrive."
            } catch (e: Exception) {
                message = "No se pudo iniciar la captura: ${e.message}"
            }
        } else {
            message = "Sin permiso de captura de pantalla no se puede activar"
            resetSignal++
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestProjection(context, projectionLauncher)
    }

    val requestCapture: () -> Unit = {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestProjection(context, projectionLauncher)
        }
    }

    // --- Permiso de superposición (widget) ---
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            if (continueNotificationStart) {
                continueNotificationStart = false
                requestCapture()
            } else {
                // Todavía no se activa nada: falta el consentimiento de captura
                message = "Permiso concedido. Mantén pulsado para iniciar."
            }
        } else {
            continueNotificationStart = false
        }
    }

    val onRequestOverlayPermission: () -> Unit = {
        try {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri()
                )
            )
        } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    val onStart: () -> Unit = {
        when {
            // 1) Sin permiso de superposición no se puede dibujar el widget
            !Settings.canDrawOverlays(context) -> onRequestOverlayPermission()

            // 2) Se pide el consentimiento de captura. Los servicios (OCR y
            //    widget) SOLO arrancan si el usuario lo concede.
            else -> requestCapture()
        }
    }

    LaunchedEffect(mainActivity?.pendingNotificationStart) {
        if (mainActivity?.pendingNotificationStart == true) {
            mainActivity.consumeNotificationStart()
            continueNotificationStart = true
            onStart()
        }
    }

    AuroraBackground(modifier = Modifier.fillMaxSize()) {
        AppNavHost(
            navController = navController,
            home = {
                HomeScreen(
                    active = servicesActive,
                    onStart = onStart,
                    onStop = { stopEverything(context) },
                    resetSignal = resetSignal,
                    onOpenSettings = {
                        navController.navigate(Routes.SETTINGS) {
                            launchSingleTop = true
                        }
                    }
                )
            },
            settings = { onBack ->
                SettingsScreen(
                    settings = settings,
                    onSettingsChange = { InspectorRepository.updateSettings(context, it) },
                    onBack = onBack
                )
            }
        )

        message?.let {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 32.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                    modifier = Modifier
                        .background(Surface1, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

// --------------------------------------------------------------------------------------------
// Home
// --------------------------------------------------------------------------------------------

@Composable
private fun HomeScreen(
    active: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    resetSignal: Int,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp)
    ) {

        // Nombre de la app arriba a la izquierda
        Column(
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Text(
                text = "InDrive",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF5F5F5)
            )
            Text(
                text = "Inspector",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.SemiBold,
                color = Lime
            )
        }

        // Ajustes arriba a la derecha
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp)
                .padding(top = 6.dp)
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Ajustes",
                tint = Color(0xFFF5F5F5)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 96.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HoldToStartButton(
                onCompleted = onStart,
                active = active,
                onStop = onStop,
                resetSignal = resetSignal,
                durationMillis = 800
            )
        }
    }
}

// --------------------------------------------------------------------------------------------
// Ajustes
// --------------------------------------------------------------------------------------------

@Composable
private fun SettingsScreen(
    settings: ProfitSettings,
    onSettingsChange: (ProfitSettings) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver",
                    tint = Color.White.copy(alpha = 0.85f)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Ajustes",
                style = MaterialTheme.typography.displayMedium,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(4.dp))

        DarkCard {
            Text(
                "Rentabilidad",
                style = MaterialTheme.typography.titleMedium,
                color = TextHigh
            )
            Spacer(Modifier.height(16.dp))

            NumberField(
                value = settings.costPerKm,
                label = "Precio por km",
                onChange = { onSettingsChange(settings.copy(costPerKm = it)) }
            )
            Spacer(Modifier.height(10.dp))
            NumberField(
                value = settings.targetPricePerHour,
                label = "Precio por hora rentable",
                onChange = { onSettingsChange(settings.copy(targetPricePerHour = it)) }
            )
            Spacer(Modifier.height(10.dp))
            NumberField(
                value = settings.minimumTripPrice,
                label = "Precio mínimo del viaje",
                onChange = { onSettingsChange(settings.copy(minimumTripPrice = it)) }
            )

            Spacer(Modifier.height(14.dp))
            FilterChip(
                selected = settings.includeDeadhead,
                onClick = {
                    onSettingsChange(settings.copy(includeDeadhead = !settings.includeDeadhead))
                },
                label = { Text("Contar el trayecto hasta el punto A") },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Surface2,
                    labelColor = TextMid,
                    selectedContainerColor = Lime,
                    selectedLabelColor = Ink
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = settings.includeDeadhead,
                    borderColor = Outline,
                    selectedBorderColor = Lime
                )
            )

            Spacer(Modifier.height(10.dp))
            FilterChip(
                selected = settings.alwaysVisible,
                onClick = {
                    onSettingsChange(settings.copy(alwaysVisible = !settings.alwaysVisible))
                },
                label = { Text("Widget siempre visible") },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Surface2,
                    labelColor = TextMid,
                    selectedContainerColor = Lime,
                    selectedLabelColor = Ink
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = settings.alwaysVisible,
                    borderColor = Outline,
                    selectedBorderColor = Lime
                )
            )
        }

        DarkCard {
            Text(
                "Semáforo",
                style = MaterialTheme.typography.titleMedium,
                color = TextHigh
            )
            Spacer(Modifier.height(12.dp))
            LegendRow(StatusGreen, "Rentable", "cumple los tres objetivos")
            LegendRow(StatusYellow, "Al límite", "hasta un 15% por debajo")
            LegendRow(StatusRed, "No rentable", "más de un 15% por debajo")
        }
    }
}

@Composable
private fun DarkCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Surface1)
    ) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

@Composable
private fun LegendRow(color: Color, title: String, subtitle: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = TextHigh
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextLow
            )
        }
    }
}

@Composable
private fun NumberField(
    value: Double,
    label: String,
    onChange: (Double) -> Unit
) {
    OutlinedTextField(
        value = if (value == 0.0) "" else trimNumber(value),
        onValueChange = { text ->
            val parsed = text.replace(',', '.').toDoubleOrNull()
            onChange(parsed ?: 0.0)
        },
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.titleMedium.copy(color = TextHigh),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Surface2,
            unfocusedContainerColor = Surface2,
            focusedBorderColor = Lime,
            unfocusedBorderColor = Outline,
            focusedLabelColor = Lime,
            unfocusedLabelColor = TextMid,
            cursorColor = Lime,
            focusedTextColor = TextHigh,
            unfocusedTextColor = TextHigh
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

// --------------------------------------------------------------------------------------------
// Utilidades
// --------------------------------------------------------------------------------------------

private fun startServices(context: Context) {
    try {
        ContextCompat.startForegroundService(context, OcrWatchService.startIntent(context))
        ContextCompat.startForegroundService(context, OverlayService.startIntent(context))
    } catch (e: Exception) {
        Log.w("InDriveInspector", "Arranque: ${e.message}")
        Toast.makeText(context, "Arranque: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

/** Para todo: widget, OCR y captura de pantalla. */
private fun stopEverything(context: Context) {
    try {
        context.stopService(Intent(context, OverlayService::class.java))
        context.stopService(Intent(context, OcrWatchService::class.java))
        context.stopService(Intent(context, ScreenCaptureService::class.java))
        InspectorRepository.setServicesActive(false)
        Notifications.showStopped(context)
    } catch (e: Exception) {
        Log.w("InDriveInspector", "Parada: ${e.message}")
    }
}

private fun requestProjection(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
        as? MediaProjectionManager
    if (manager == null) {
        Toast.makeText(context, "MediaProjection no disponible", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        launcher.launch(manager.createScreenCaptureIntent())
    } catch (e: Exception) {
        Toast.makeText(context, "No se pudo pedir la captura: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun trimNumber(value: Double): String =
    if (value % 1.0 == 0.0) {
        String.format(Locale.US, "%.0f", value)
    } else {
        String.format(Locale.US, "%.1f", value)
    }
