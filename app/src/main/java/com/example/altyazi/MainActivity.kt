package com.example.altyazi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Root()
            }
        }
    }
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    if (granted) {
        CaptionScreen()
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text("Kamera izni ver")
            }
        }
    }
}

@Composable
fun CaptionScreen() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val engine = remember { CaptionEngine(ctx) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            engine.close()
            executor.shutdown()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {

        // Kamera önizlemesi + altyazı alanı çerçevesi
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    val view = PreviewView(c).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                    }
                    val future = ProcessCameraProvider.getInstance(c)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(view.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { it.setAnalyzer(executor, engine::analyze) }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    }, ContextCompat.getMainExecutor(c))
                    view
                }
            )
            Canvas(Modifier.fillMaxSize()) {
                val y = engine.top * size.height
                val h = engine.regionHeight * size.height
                drawRect(
                    color = Color(0xFF00E676),
                    topLeft = Offset(0f, y),
                    size = Size(size.width, h),
                    style = Stroke(width = 4f)
                )
            }
        }

        // Kontroller
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(engine.status, style = MaterialTheme.typography.labelMedium)

            Button(
                onClick = { engine.running = !engine.running },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (engine.running) "Durdur" else "Başlat")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = engine.speak, onCheckedChange = { engine.speak = it })
                Text("  Sesli oku")
            }

            Text("Altyazı alanı: konum")
            Slider(
                value = engine.top,
                onValueChange = { engine.top = it },
                valueRange = 0f..0.9f
            )
            Text("Altyazı alanı: yükseklik")
            Slider(
                value = engine.regionHeight,
                onValueChange = { engine.regionHeight = it },
                valueRange = 0.05f..0.5f
            )

            if (engine.original.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(engine.original, style = MaterialTheme.typography.bodySmall)
                        Text(
                            engine.translated,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
