package com.mazwas.coverlauncher.feature.launcher.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.mazwas.coverlauncher.core.system.TAG

/**
 * Dictation for the reply box: Android's speech recognizer (Google's on this phone) in our own process, no UI of its
 * own. The recognizer's activity (`RECOGNIZE_SPEECH`) belongs to an app that isn't on the cover list, so it isn't used.
 * Needs RECORD_AUDIO (Grants). Main thread only.
 */
class VoiceInput(private val context: Context) {
  private var recognizer: SpeechRecognizer? = null

  /** [onText] gets the words so far, then the final ones; [onEnd] once it stops by itself (done, silence, error). */
  fun listen(onText: (String) -> Unit, onEnd: () -> Unit) {
    stop()
    val r = SpeechRecognizer.createSpeechRecognizer(context)
    recognizer = r
    r.setRecognitionListener(object : RecognitionListener {
      override fun onPartialResults(results: Bundle) {
        text(results)?.let(onText)
      }

      override fun onResults(results: Bundle) {
        text(results)?.let(onText)
        end()
      }

      override fun onError(error: Int) {
        Log.w(TAG, "dictation error $error")
        end()
      }

      private fun end() {
        stop()
        onEnd()
      }

      override fun onReadyForSpeech(params: Bundle?) = Unit
      override fun onBeginningOfSpeech() = Unit
      override fun onRmsChanged(rmsdB: Float) = Unit
      override fun onBufferReceived(buffer: ByteArray?) = Unit
      override fun onEndOfSpeech() = Unit
      override fun onEvent(eventType: Int, params: Bundle?) = Unit
    })
    r.startListening(
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
    )
  }

  fun stop() {
    recognizer?.destroy()
    recognizer = null
  }

  private fun text(results: Bundle) =
    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
