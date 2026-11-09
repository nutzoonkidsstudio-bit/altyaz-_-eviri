package com.example.altyazi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

/**
 * Kamera karesi -> altyazı alanını kes -> OCR -> Türkçeye çevir -> seslendir.
 */
class CaptionEngine(context: Context) {

    // Arayüzün okuduğu/yazdığı durum
    var running by mutableStateOf(false)
    var speak by mutableStateOf(true)
    var top by mutableFloatStateOf(0.65f)          // alanın üst kenarı (0..1)
    var regionHeight by mutableFloatStateOf(0.20f) // alanın yüksekliği (0..1)
    var original by mutableStateOf("")
    var translated by mutableStateOf("")
    var status by mutableStateOf("Çeviri modeli indiriliyor…")

    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
    )

    private var tts: TextToSpeech? = null

    @Volatile private var ready = false
    @Volatile private var busy = false
    private var lastRun = 0L
    private var lastWords: Set<String> = emptySet()

    init {
        tts = TextToSpeech(context.applicationContext) { code ->
            if (code == TextToSpeech.SUCCESS) {
                tts?.language = Locale("tr", "TR")
            }
        }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                ready = true
                status = "Hazır"
            }
            .addOnFailureListener {
                status = "Model indirilemedi, internet bağlantını kontrol et"
            }
    }

    /** CameraX her kare için bunu çağırır. */
    fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!running || !ready || busy || now - lastRun < 700) {
            proxy.close()
            return
        }
        busy = true
        lastRun = now

        val crop: Bitmap? = try {
            val src = proxy.toBitmap()
            val m = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            val full = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val y = (top.coerceIn(0f, 0.95f) * full.height).toInt().coerceAtMost(full.height - 1)
            val h = (regionHeight * full.height).toInt().coerceIn(1, full.height - y)
            Bitmap.createBitmap(full, 0, y, full.width, h)
        } catch (e: Exception) {
            null
        } finally {
            proxy.close()
        }

        if (crop == null) {
            busy = false
            return
        }

        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { handle(it.text) }
            .addOnFailureListener { busy = false }
    }

    private fun handle(raw: String) {
        val clean = raw.replace("\n", " ").trim()
        val words = clean.lowercase()
            .split(Regex("[^\\p{L}0-9]+"))
            .filter { it.isNotEmpty() }
            .toSet()

        // Çok kısa ya da bir önceki satırla neredeyse aynıysa atla
        if (clean.length < 3 || words.isEmpty() || similar(words, lastWords)) {
            busy = false
            return
        }
        lastWords = words
        original = clean

        translator.translate(clean)
            .addOnSuccessListener { tr ->
                translated = tr
                if (speak) {
                    tts?.speak(tr, TextToSpeech.QUEUE_FLUSH, null, "caption")
                }
                busy = false
            }
            .addOnFailureListener { busy = false }
    }

    private fun similar(a: Set<String>, b: Set<String>): Boolean {
        if (b.isEmpty()) return false
        val inter = a.intersect(b).size.toFloat()
        return inter / a.union(b).size >= 0.7f
    }

    fun close() {
        recognizer.close()
        translator.close()
        tts?.stop()
        tts?.shutdown()
    }
}
