package com.nexus.cli.nexusdrawer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

class NexusDrawerViewModel : ViewModel() {

    private val _state = MutableStateFlow(NexusPresenceState.IDLE)
    val state: StateFlow<NexusPresenceState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _statusText = MutableStateFlow(NexusPresenceState.IDLE.defaultStatus)
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _message = MutableStateFlow("Haloo! Aku Nexus.")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _isOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private val _isMicOn = MutableStateFlow(false)
    val isMicOn: StateFlow<Boolean> = _isMicOn.asStateFlow()

    private var settleJob: Job? = null
    private var ampJob: Job? = null

    @Volatile
    private var lastExternalAmpMs = 0L

    // ---------- Drawer ----------

    fun open() { _isOpen.value = true }
    fun close() { _isOpen.value = false }
    fun setOpen(open: Boolean) { _isOpen.value = open }
    fun toggle() { _isOpen.value = !_isOpen.value }

    // ---------- Mic ----------

    /** Dipanggil MainActivity.setMicState(). Tidak mengganggu THINKING/SPEAKING/ERROR yang sedang berjalan. */
    fun setListening(enabled: Boolean) {
        _isMicOn.value = enabled
        val s = _state.value
        if (s == NexusPresenceState.THINKING || s == NexusPresenceState.SPEAKING || s == NexusPresenceState.ERROR) return
        applyState(restingState())
    }

    // ---------- Perintah shell ----------

    fun onCommandStart(command: String?) {
        settleJob?.cancel()
        val status = if (command.isNullOrBlank()) {
            NexusPresenceState.THINKING.defaultStatus
        } else {
            "Menjalankan ${command.take(20)}…"
        }
        applyState(NexusPresenceState.THINKING, status)
    }

    fun onCommandResult(summary: String, isError: Boolean) {
        settleJob?.cancel()
        _message.value = summary
        if (isError) {
            applyState(NexusPresenceState.ERROR)
            settleAfter(3500L)
        } else {
            applyState(NexusPresenceState.SPEAKING)
            settleAfter((summary.length * 30L + 1500L).coerceAtMost(8000L))
        }
    }

    /** Dipakai untuk pesan bebas (mis. sapaan). */
    fun say(text: String, state: NexusPresenceState = NexusPresenceState.SPEAKING) {
        settleJob?.cancel()
        _message.value = text
        applyState(state)
        settleAfter((text.length * 30L + 1500L).coerceAtMost(8000L))
    }

    /** Hubungkan ke RMS mikrofon asli jika ada (0..1). Selama masuk, amplitudo simulasi dilewati. */
    fun setAmplitude(value: Float) {
        lastExternalAmpMs = System.currentTimeMillis()
        _amplitude.value = value.coerceIn(0f, 1f)
    }

    // ---------- Internal ----------

    private fun restingState() =
        if (_isMicOn.value) NexusPresenceState.LISTENING else NexusPresenceState.IDLE

    private fun settleAfter(ms: Long) {
        settleJob = viewModelScope.launch {
            delay(ms)
            applyState(restingState())
        }
    }

    private fun applyState(s: NexusPresenceState, status: String = s.defaultStatus) {
        _state.value = s
        _statusText.value = status
        updateAmplitudeLoop(s)
    }

    private fun updateAmplitudeLoop(s: NexusPresenceState) {
        ampJob?.cancel()
        when (s) {
            NexusPresenceState.LISTENING, NexusPresenceState.SPEAKING -> {
                ampJob = viewModelScope.launch {
                    var a = 0.3f
                    while (true) {
                        if (System.currentTimeMillis() - lastExternalAmpMs > 300L) {
                            a = (a * 0.5f + Random.nextFloat() * 0.5f + 0.2f).coerceIn(0.1f, 1f)
                            _amplitude.value = if (s == NexusPresenceState.LISTENING) a * 0.8f else a
                        }
                        delay(90)
                    }
                }
            }
            NexusPresenceState.THINKING -> _amplitude.value = 0.15f
            else -> _amplitude.value = 0f
        }
    }
}