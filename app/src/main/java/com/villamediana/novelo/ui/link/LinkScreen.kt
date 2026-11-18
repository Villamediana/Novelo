package com.villamediana.novelo.ui.link

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.sync.NoveloWeb
import com.villamediana.novelo.ui.Nav
import com.villamediana.novelo.ui.components.IconAction
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.paper
import com.villamediana.novelo.ui.home.QuietIcon
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** Where linking a computer is: reading its code, asking her, linking, done (or it couldn't). */
private sealed interface Step {
    data object Reading : Step
    data class Asking(val code: NoveloWeb.Code) : Step
    data object Linking : Step
    data class Linked(val name: String) : Step
    data class Failed(val message: String) : Step
}

/**
 * Linking a computer to the Novelo: the camera reads the code the page shows (or the code came from
 * the phone's own camera: [code]); she says yes, the phone sends the space's key to that computer,
 * encrypted to it, and everything starts going there.
 */
@Composable
fun LinkScreen(nav: Nav, code: NoveloWeb.Code? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(if (code != null) Step.Asking(code) else Step.Reading) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted && code == null) ask.launch(Manifest.permission.CAMERA) }

    fun link(c: NoveloWeb.Code) {
        step = Step.Linking
        scope.launch {
            step = runCatching { Step.Linked(NoveloApplication.graph.web.link(c)) }.getOrElse { e ->
                Step.Failed(
                    when ((e as? com.villamediana.novelo.server.ServerException)?.code) {
                        "expired" -> t(R.string.web_code_expired)
                        "too_many" -> t(R.string.web_too_many)
                        else -> if (e is java.io.IOException) t(R.string.web_offline) else t(R.string.web_failed)
                    },
                )
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (step is Step.Reading && granted) Scanner { found -> if (step is Step.Reading) step = Step.Asking(found) }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            QuietIcon(NoveloIcons.Back, t(R.string.back), { nav.back() }, alpha = 0.9f)
            Spacer(Modifier.weight(1f))
            if (step is Step.Reading) {
                // Where to hold the code.
                Box(Modifier.align(Alignment.CenterHorizontally).size(250.dp).border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(28.dp)))
                Spacer(Modifier.weight(1f))
            }
            AnimatedContent(step, label = "step") { s ->
                Column(
                    Modifier.fillMaxWidth().padding(16.dp).background(NoveloColors.Background, RoundedCornerShape(28.dp)).paper().padding(22.dp).testTag("link-card"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (s) {
                        Step.Reading -> {
                            Text(t(R.string.web_scan_title), style = MaterialTheme.typography.titleLarge, color = NoveloColors.Text, textAlign = TextAlign.Center)
                            Text(if (granted) t(R.string.web_scan_hint) else t(R.string.web_scan_no_camera), style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted, textAlign = TextAlign.Center)
                        }
                        is Step.Asking -> {
                            Text(t(R.string.web_link_ask), style = MaterialTheme.typography.titleLarge, color = NoveloColors.Text, textAlign = TextAlign.Center)
                            Text(t(R.string.web_link_ask_hint), style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted, textAlign = TextAlign.Center)
                            IconAction(NoveloIcons.Check, t(R.string.web_link_do), { link(s.code) }, primary = true, size = 60.dp, modifier = Modifier.testTag("link-yes"))
                        }
                        Step.Linking -> {
                            CircularProgressIndicator(color = NoveloColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(34.dp))
                            Text(t(R.string.web_linking), style = MaterialTheme.typography.bodyLarge, color = NoveloColors.TextMuted)
                        }
                        is Step.Linked -> {
                            Text(t(R.string.web_linked), style = MaterialTheme.typography.titleLarge, color = NoveloColors.Text, textAlign = TextAlign.Center)
                            Text(t(R.string.web_linked_hint, s.name.ifBlank { t(R.string.web_computer) }), style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted, textAlign = TextAlign.Center)
                            IconAction(NoveloIcons.Check, t(R.string.ok), { nav.back() }, primary = true)
                        }
                        is Step.Failed -> {
                            Text(s.message, style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text, textAlign = TextAlign.Center)
                            IconAction(NoveloIcons.Retry, t(R.string.try_again), { step = Step.Reading }, primary = true)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

/** The back camera looking for a computer's code (ML Kit, on the phone): [onCode] when it sees one. */
@Composable
private fun Scanner(onCode: (NoveloWeb.Code) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { frame ->
                val img = frame.image
                if (img == null) {
                    frame.close()
                    return@setAnalyzer
                }
                scanner.process(InputImage.fromMediaImage(img, frame.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { NoveloWeb.code(it.rawValue) }?.let { found -> view.post { onCode(found) } } }
                    .addOnCompleteListener { frame.close() }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}
