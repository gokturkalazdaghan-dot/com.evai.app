// android/app/src/main/java/com/eva/app/ui/eva/EvaSpeech.kt
package com.eva.app.ui.eva

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

private const val TAG = "EvaSpeech"

/**
 * Uygulama one gelmisken surekli dinler. "hey Eva" ViewModel'de
 * ayiklanir. SpeechRecognizer her sonuc/hatadan sonra yeniden
 * baslatilir — Android'de surekli dinlemenin tek resmi yolu budur.
 */
@Composable
fun EvaSpeechSession(
    enabled: Boolean,
    onHeard: (String) -> Unit,
    onListeningChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    DisposableEffect(enabled) {
        if (!enabled) {
            onListeningChange(false)
            onDispose { }
        } else {
            val session = EvaRecognizerSession(context, onHeard, onListeningChange)
            session.start()
            onDispose { session.release() }
        }
    }
}

@Composable
fun EvaTtsSession(viewModel: EvaChatViewModel) {
    val context = LocalContext.current
    DisposableEffect(viewModel) {
        val tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                viewModel.attachSpeaker(tts)
            }
        }
        onDispose {
            viewModel.detachSpeaker()
            tts.stop()
            tts.shutdown()
        }
    }
}

private class EvaRecognizerSession(
    context: Context,
    private val onHeard: (String) -> Unit,
    private val onListeningChange: (Boolean) -> Unit,
) : RecognitionListener {

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var released = false

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.w(TAG, "Konusma tanima yok")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(this)
        }
        listen()
    }

    fun release() {
        released = true
        onListeningChange(false)
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        if (released) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Dinleme baslamadi", e)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        onListeningChange(true)
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() {
        onListeningChange(false)
    }

    override fun onError(error: Int) {
        onListeningChange(false)
        if (!released) listen()
    }

    override fun onResults(results: Bundle?) {
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        if (text.isNotBlank()) onHeard(text)
        if (!released) listen()
    }

    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
