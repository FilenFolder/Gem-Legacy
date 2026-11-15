package com.filenfolder.gemlegacy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.HashMap
import java.util.Locale

class VoiceCall(private val activity: Activity, private val listener: Listener) {

    enum class State { STARTING, LISTENING, THINKING, SPEAKING }

    interface Listener {
        fun onState(state: State)
        fun onHeard(text: String)
        fun onUserSaid(text: String)
        fun onFatal(message: String)
    }

    var state = State.STARTING
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private var active = false
    private var audioEntered = false
    private var speakerOn = false
    private var listening = false
    private var failures = 0
    private var savedMode = AudioManager.MODE_NORMAL

    private var currentSpeech = ""
    private var speechEndedAt = 0L
    private var speakToken = 0
    private var lastChunkId = ""

    fun start() {
        if (active) return
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            listener.onFatal(activity.getString(R.string.call_no_recognizer))
            return
        }
        active = true
        changeState(State.STARTING)
        tts = TextToSpeech(activity.applicationContext, TextToSpeech.OnInitListener { status ->
            handler.post { onTtsReady(status) }
        })
    }

    fun end() {
        if (!active && !audioEntered) return
        active = false
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        leaveAudioMode()
    }

    fun setSpeaker(on: Boolean) {
        speakerOn = on
        try {
            audio.isSpeakerphoneOn = on
        } catch (e: Exception) {
        }
    }

    fun speak(text: String) {
        if (!active) return
        val chunks = chunk(plain(text))
        if (chunks.isEmpty()) {
            resumeListening()
            return
        }
        currentSpeech = chunks.joinToString(" ")
        speakToken++
        val token = speakToken
        lastChunkId = "t${token}_${chunks.size - 1}"
        changeState(State.SPEAKING)
        for ((i, piece) in chunks.withIndex()) {
            val params = HashMap<String, String>()
            params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = "t${token}_$i"
            params[TextToSpeech.Engine.KEY_PARAM_STREAM] = AudioManager.STREAM_VOICE_CALL.toString()
            tts?.speak(piece, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params)
        }
        if (!listening) restartListening(200)
    }

    fun resumeListening() {
        if (!active) return
        changeState(State.LISTENING)
        if (!listening) restartListening(100)
    }

    private fun onTtsReady(status: Int) {
        if (!active) return
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            listener.onFatal(activity.getString(R.string.call_no_tts))
            return
        }
        val result = engine.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                handler.post { onUtteranceDone(utteranceId) }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                handler.post { onUtteranceDone(utteranceId) }
            }
        })

        enterAudioMode()
        recognizer = SpeechRecognizer.createSpeechRecognizer(activity).also { it.setRecognitionListener(recListener) }
        changeState(State.LISTENING)
        startRecognizing()
    }

    private fun enterAudioMode() {
        audioEntered = true
        try {
            savedMode = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.isSpeakerphoneOn = speakerOn
        } catch (e: Exception) {
        }
        muteBeeps(true)
        activity.volumeControlStream = AudioManager.STREAM_VOICE_CALL
    }

    private fun leaveAudioMode() {
        if (!audioEntered) return
        audioEntered = false
        muteBeeps(false)
        try {
            audio.isSpeakerphoneOn = false
            audio.mode = savedMode
        } catch (e: Exception) {
        }
        activity.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
    }

    @Suppress("DEPRECATION")
    private fun muteBeeps(mute: Boolean) {
        try {
            audio.setStreamMute(AudioManager.STREAM_MUSIC, mute)
            audio.setStreamMute(AudioManager.STREAM_SYSTEM, mute)
            audio.setStreamMute(AudioManager.STREAM_NOTIFICATION, mute)
        } catch (e: Exception) {
        }
    }

    private fun startRecognizing() {
        if (!active) return
        val r = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        try {
            listening = true
            r.startListening(intent)
        } catch (e: Exception) {
            listening = false
            noteFailure(activity.getString(R.string.call_recognizer_error))
        }
    }

    private fun restartListening(delayMs: Long) {
        handler.postDelayed({
            val canListen = state == State.LISTENING || state == State.SPEAKING
            if (active && !listening && canListen) startRecognizing()
        }, delayMs)
    }

    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {
            if (state == State.SPEAKING && !speakerOn) bargeIn()
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            if (text.isBlank()) return
            if (state == State.SPEAKING) {
                if (!looksLikeEcho(text)) {
                    bargeIn()
                    listener.onHeard(text)
                }
            } else if (state == State.LISTENING) {
                listener.onHeard(text)
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            failures = 0
            handleResult(firstResult(results).trim())
        }

        override fun onError(error: Int) {
            listening = false
            handleError(error)
        }
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""

    private fun handleResult(text: String) {
        if (!active || state == State.THINKING || state == State.STARTING) return
        if (text.isEmpty()) {
            restartListening(250)
            return
        }
        val recentlySpoke = System.currentTimeMillis() - speechEndedAt < ECHO_WINDOW_MS
        if ((state == State.SPEAKING || recentlySpoke) && looksLikeEcho(text)) {
            restartListening(250)
            return
        }
        if (state == State.SPEAKING) bargeIn()
        changeState(State.THINKING)
        listener.onUserSaid(text)
    }

    private fun handleError(error: Int) {
        if (!active) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                restartListening(300)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                try {
                    recognizer?.cancel()
                } catch (e: Exception) {
                }
                restartListening(700)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                listener.onFatal(activity.getString(R.string.no_mic))
            }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                noteFailure(activity.getString(R.string.call_network_error))
            }
            else -> {
                noteFailure(activity.getString(R.string.call_recognizer_error) + " ($error)")
            }
        }
    }

    private fun noteFailure(message: String) {
        failures++
        if (failures >= MAX_FAILURES) listener.onFatal(message) else restartListening(900)
    }

    private fun bargeIn() {
        if (state != State.SPEAKING) return
        speakToken++
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
        speechEndedAt = 0L
        changeState(State.LISTENING)
    }

    private fun onUtteranceDone(id: String?) {
        if (!active || state != State.SPEAKING || id != lastChunkId) return
        speechEndedAt = System.currentTimeMillis()
        changeState(State.LISTENING)
        if (!listening) restartListening(200)
    }

    private fun looksLikeEcho(heard: String): Boolean {
        val words = words(heard)
        if (words.isEmpty()) return true
        val spoken = words(currentSpeech).toHashSet()
        val hits = words.count { it in spoken }
        return hits * 100 / words.size >= 70
    }

    private fun words(text: String): List<String> =
        text.lowercase(Locale.getDefault()).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun plain(text: String): String =
        text.replace(Regex("[*#`_]+"), "")
            .replace("\u2022", "")
            .replace(Regex("\\s*\\n+\\s*"), ". ")
            .replace(Regex("\\.\\s*\\.(\\s*\\.)*"), ".")
            .trim()

    private fun chunk(text: String): List<String> {
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > MAX_CHUNK) {
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', ';', ','), MAX_CHUNK)
            if (cut < 80) cut = rest.lastIndexOf(' ', MAX_CHUNK)
            if (cut < 1) cut = MAX_CHUNK
            val piece = rest.substring(0, cut + 1).trim()
            if (piece.isNotEmpty()) out.add(piece)
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    private fun changeState(newState: State) {
        state = newState
        listener.onState(newState)
    }

    companion object {
        private const val MAX_CHUNK = 300
        private const val MAX_FAILURES = 4
        private const val ECHO_WINDOW_MS = 3000L
    }
}
