package com.build.mythos

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.content.Intent

/**
 * Pendaftaran MYTHOS sebagai "Aplikasi asisten digital" Android. Setelah dipilih di Pengaturan > Aplikasi >
 * Aplikasi default > Asisten digital, tahan tombol power (atau gestur asisten) memanggil [AssistSession] di atas
 * aplikasi apa pun yang sedang dibuka.
 */
class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/** Android mewajibkan asisten digital menyebut layanan pengenal suara. MYTHOS tidak mengenali suara: selalu mengembalikan galat. */
class AssistRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try { listener?.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) {}
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
