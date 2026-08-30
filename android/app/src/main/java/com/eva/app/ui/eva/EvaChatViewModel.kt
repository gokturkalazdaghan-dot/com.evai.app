// android/app/src/main/java/com/eva/app/ui/eva/EvaChatViewModel.kt
package com.eva.app.ui.eva

import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class EvaChatUiState(
    val messages: List<EvaChatMessage> = emptyList(),
    val draft: String = "",
    val listening: Boolean = false,
    val sessionActive: Boolean = false,
    val thinking: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class EvaChatViewModel @Inject constructor(
    private val repository: EvaChatRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EvaChatUiState())
    val state: StateFlow<EvaChatUiState> = _state.asStateFlow()

    private var nextId = 1L
    private var speaker: TextToSpeech? = null

    fun attachSpeaker(tts: TextToSpeech) {
        speaker = tts
        tts.language = Locale.getDefault()
    }

    fun detachSpeaker() {
        speaker = null
    }

    fun setListening(listening: Boolean) {
        _state.update { it.copy(listening = listening) }
    }

    fun onDraftChange(value: String) {
        _state.update { it.copy(draft = value) }
    }

    /**
     * Mikrofon metni. Oturum kapaliysa yalnizca "hey Eva" uyandirir;
     * oturum aciksa her cumle Grok'a gider.
     */
    fun onHeard(transcript: String) {
        val current = _state.value
        if (current.thinking) return

        if (!current.sessionActive) {
            val wake = EvaWakeParser.parse(transcript) ?: return
            activate()
            if (wake.afterWake.isBlank()) {
                greet()
            } else {
                send(wake.afterWake)
            }
            return
        }

        val wake = EvaWakeParser.parse(transcript)
        val text = wake?.afterWake?.ifBlank { null } ?: transcript.trim()
        if (text.isBlank()) return
        send(text)
    }

    fun sendDraft() {
        val text = _state.value.draft.trim()
        if (text.isBlank() || _state.value.thinking) return
        _state.update { it.copy(draft = "") }
        if (!_state.value.sessionActive) activate()
        val wake = EvaWakeParser.parse(text)
        send(wake?.afterWake?.ifBlank { text } ?: text)
    }

    fun activate() {
        _state.update { it.copy(sessionActive = true, error = null) }
    }

    private fun greet() {
        val hello = localHello()
        append("assistant", hello, local = true)
        speak(hello)
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.thinking) return

        append("user", trimmed)
        _state.update { it.copy(thinking = true, error = null, sessionActive = true) }

        viewModelScope.launch {
            val history = _state.value.messages
                .filter { !it.isLocal && it.role != "system" }
                .dropLast(1)
                .takeLast(30)
                .map { EvaChatTurnDto(role = it.role, content = it.text) }

            when (val result = repository.chat(trimmed, history)) {
                is EvaChatResult.Success -> {
                    append("assistant", result.reply)
                    speak(result.reply)
                    _state.update { it.copy(thinking = false) }
                }
                is EvaChatResult.Failure -> {
                    _state.update { it.copy(thinking = false, error = result.message) }
                }
            }
        }
    }

    private fun append(role: String, text: String, local: Boolean = false) {
        val message = EvaChatMessage(
            id = nextId++,
            role = role,
            text = text,
            isLocal = local,
        )
        _state.update { it.copy(messages = it.messages + message) }
    }

    private fun speak(text: String) {
        speaker?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "eva-${nextId}")
    }

    private fun localHello(): String {
        return when (Locale.getDefault().language) {
            "tr" -> "Dinliyorum. Söyle, buradayım."
            "de" -> "Ich höre. Sag einfach, ich bin da."
            "fr" -> "Je t'écoute. Dis-moi, je suis là."
            "es" -> "Te escucho. Dime, estoy aquí."
            else -> "I'm listening. Go ahead, I'm here."
        }
    }

    override fun onCleared() {
        speaker = null
        super.onCleared()
    }
}
