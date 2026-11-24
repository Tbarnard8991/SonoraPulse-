package com.sonorapulse.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sonorapulse.app.billing.ProBilling
import com.sonorapulse.app.export.ExportResult
import com.sonorapulse.app.ui.SonoraPulseViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // mandatory look on API 36; content must handle insets
        super.onCreate(savedInstanceState)
        ProBilling.init(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SonoraPulseScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Restores Pro (or picks up a purchase finished elsewhere) and acknowledges it if needed.
        ProBilling.refreshPurchases()
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun SonoraPulseScreen(vm: SonoraPulseViewModel = viewModel()) {
    val context = LocalContext.current
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val isRunning by vm.isRunning.collectAsStateWithLifecycle()
    val sampleRate by vm.sampleRate.collectAsStateWithLifecycle()
    val isPro by ProBilling.isPro.collectAsStateWithLifecycle()
    val proPrice by ProBilling.price.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        ProBilling.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    val buyPro: () -> Unit = {
        val activity = context.findActivity()
        if (activity != null) ProBilling.buyPro(activity)
    }

    // Android blocks mic input for backgrounded apps, so stop cleanly instead.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stopIfRunning() }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.toggleCapture()
        else Toast.makeText(context, "Microphone access is needed to analyze audio", Toast.LENGTH_LONG).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("SonoraPulse", style = MaterialTheme.typography.headlineMedium)
            if (isPro) {
                Text(
                    "PRO",
                    color = Color(0xFF1D4ED8),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        // Exact frequency numbers are Pro; the level meter stays free.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricText("PEAK", "%.1f Hz".format(telemetry.peakFreqHz), locked = !isPro)
            MetricText("LEVEL", "%.1f dBFS".format(telemetry.rmsDbfs))
            MetricText("CENTROID", "%.0f Hz".format(telemetry.spectralCentroidHz), locked = !isPro)
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(220.dp)) {
            val bars = telemetry.spectrumBars
            val barW = size.width / bars.size
            bars.forEachIndexed { idx, value ->
                val h = value * size.height
                drawRect(
                    color = Color(0xFF1D4ED8),
                    topLeft = Offset(idx * barW + 2f, size.height - h),
                    size = Size(barW - 4f, h)
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricText("SUB", "%.0f dB".format(telemetry.subBassDb), locked = !isPro)
            MetricText("BASS", "%.0f dB".format(telemetry.bassDb), locked = !isPro)
            MetricText("MID", "%.0f dB".format(telemetry.midDb), locked = !isPro)
            MetricText("PRES", "%.0f dB".format(telemetry.presenceDb), locked = !isPro)
            MetricText("BRIL", "%.0f dB".format(telemetry.brillianceDb), locked = !isPro)
        }

        if (sampleRate > 0) {
            Text(
                "Stream: $sampleRate Hz · 2048-pt FFT · 50% overlap",
                style = MaterialTheme.typography.labelSmall
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val hasPerm = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPerm) vm.toggleCapture()
                else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }) {
                Text(if (isRunning) "Stop" else "Start Capture")
            }

            OutlinedButton(onClick = {
                if (!isPro) buyPro() else vm.exportHistory(context) { result ->
                    val msg = when (result) {
                        is ExportResult.Saved -> "Saved ${result.fileName} to Downloads/SonoraPulse"
                        ExportResult.NoSamples -> "No samples yet. Start a capture first."
                        is ExportResult.Failed -> "Export failed: ${result.reason}"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }) {
                Text(if (isPro) "Export CSV" else "\uD83D\uDD12 Export CSV")
            }
        }

        if (!isPro) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("SonoraPulse Pro", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "One-time purchase. Unlocks exact frequency readouts (peak Hz, centroid, band levels) and CSV export.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Button(onClick = buyPro) {
                            Text(proPrice?.let { "Unlock Pro \u00B7 $it" } ?: "Unlock Pro")
                        }
                        TextButton(onClick = { ProBilling.refreshPurchases(userInitiated = true) }) {
                            Text("Restore purchase")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricText(label: String, value: String, locked: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            if (locked) "\uD83D\uDD12 Pro" else value,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleMedium,
            color = if (locked) Color.Gray else Color.Unspecified
        )
    }
}
