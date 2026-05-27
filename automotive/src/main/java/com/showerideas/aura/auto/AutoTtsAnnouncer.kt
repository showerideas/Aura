package com.showerideas.aura.auto

import android.content.Context
import android.speech.tts.TextToSpeech
import timber.log.Timber
import java.util.Locale

/**
 * Thin TTS wrapper for Android Auto exchange announcements.
 *
 * Wraps [TextToSpeech] to announce key exchange lifecycle events so the
 * driver does not need to look at the screen:
 *
 * - "AURA is ready to exchange"   → when [AdvertisingScreen] becomes active
 * - "Contact received from <name>"→ when the exchange completes
 * - "Exchange cancelled"          → when the driver cancels or auto-aborts
 *
 * Threading
 * [TextToSpeech] callbacks arrive on an arbitrary thread. All public methods
 * are safe to call from the Car App Session thread or a background coroutine.
 *
 * Lifecycle
 * Call [init] when the Car App session starts.
 * Call [shutdown] when the session ends.
 */
class AutoTtsAnnouncer(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pendingQueue = ArrayDeque<String>()

    // Lifecycle

    /** Initialise the TTS engine. Must be called before any speak methods. */
    fun init() {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            ready = true
            Timber.i("AutoTtsAnnouncer: TTS engine ready")
            drainQueue()
        } else {
            Timber.w("AutoTtsAnnouncer: TTS init failed with status %d", status)
        }
    }

    /** Release TTS resources. Call from [androidx.car.app.Session] lifecycle. */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts  = null
        ready = false
        pendingQueue.clear()
    }

    // Announcement methods

    /** Announce that AURA is advertising and waiting for a nearby device. */
    fun announceAdvertising() {
        speak(context.getString(R.string.auto_tts_advertising))
    }

    /**
     * Announce a successful exchange.
     * @param contactName Display name of the contact received.
     */
    fun announceExchangeComplete(contactName: String) {
        speak(context.getString(R.string.auto_tts_exchange_complete, contactName))
    }

    /** Announce that the exchange was cancelled or timed out. */
    fun announceExchangeCancelled() {
        speak(context.getString(R.string.auto_tts_cancelled))
    }

    /** Announce a SAS verification prompt (do not read the digits for security). */
    fun announceSasRequired() {
        speak(context.getString(R.string.auto_tts_sas_required))
    }

    // Private helpers

    private fun speak(text: String) {
        if (!ready) {
            Timber.d("AutoTtsAnnouncer: queuing '%s' (TTS not ready)", text.take(30))
            pendingQueue.addLast(text)
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "aura_auto_${System.nanoTime()}")
        Timber.d("AutoTtsAnnouncer: speaking '%s'", text.take(50))
    }

    private fun drainQueue() {
        while (pendingQueue.isNotEmpty()) {
            speak(pendingQueue.removeFirst())
        }
    }
}

