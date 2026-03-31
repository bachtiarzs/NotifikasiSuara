package com.bachtiarzs.notifikasisuara

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.LinkedList
import java.util.Locale
import java.util.Queue
import java.util.regex.Pattern

class NotificationReaderService : NotificationListenerService() {

    companion object {
        var instance: NotificationReaderService? = null
        private const val TAG = "NotifReader"
        private const val CHANNEL_ID = "notif_reader_channel"
        private const val NOTIF_ID = 1

        // Daftar package GoPay Merchant yang dipantau
        val TARGET_APPS = mutableSetOf(
            "com.gojek.gopay.merchant",
            "id.gojek.app",
            "com.gojek.merchant",
            "com.gopay",
            "id.gopay"
        )
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val speakQueue: Queue<String> = LinkedList()
    private var isSpeaking = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, buildForegroundNotification())
        initTts()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        tts?.stop()
        tts?.shutdown()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("id", "ID"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
                // TTS natural, tidak kaku
                tts?.setSpeechRate(0.88f)
                tts?.setPitch(1.05f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        processQueue()
                    }
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        processQueue()
                    }
                })
                ttsReady = true
                Log.d(TAG, "TTS siap")
                processQueue()
            } else {
                Log.e(TAG, "TTS gagal inisialisasi")
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", true)
        if (!enabled) return

        val pkg = sbn.packageName ?: return

        // Cek apakah paket adalah GoPay Merchant
        val isTarget = TARGET_APPS.any { pkg.contains(it, ignoreCase = true) } ||
            pkg.contains("gopay", ignoreCase = true) ||
            pkg.contains("gojek", ignoreCase = true)
        if (!isTarget) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val bigText = extras.getString(Notification.EXTRA_BIG_TEXT) ?: text
        val fullText = bigText.ifBlank { text }

        Log.d(TAG, "Notifikasi dari: $pkg | $title | $fullText")

        // Ekstrak nominal dari notifikasi dan buat kalimat custom
        val speechText = buildPaymentSpeech(title, fullText)
        if (speechText.isNotBlank()) {
            enqueueSpeak(speechText)
        }
    }

    /**
     * Mengekstrak nominal pembayaran dari teks notifikasi GoPay Merchant.
     * Format output: "Terimakasih, Pembayaran sejumlah [nominal dalam kata] telah diterima"
     * Jika nominal tidak ditemukan, tidak membaca apapun (return blank).
     */
    private fun buildPaymentSpeech(title: String, text: String): String {
        val combined = "$title $text"

        // Pola untuk mendeteksi nominal: Rp 50.000 / Rp50000 / Rp 50,000 / IDR 50000
        val patterns = listOf(
            Pattern.compile("(?:Rp\\.?|IDR)[\\s]?([\\d][\\d.,]*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("([\\d][\\d.,]+)\\s*(?:rupiah)", Pattern.CASE_INSENSITIVE)
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(combined)
            if (matcher.find()) {
                val numStr = matcher.group(1)
                    ?.replace(".", "")
                    ?.replace(",", "")
                    ?.trim() ?: continue
                val num = numStr.toLongOrNull() ?: continue
                if (num <= 0) continue

                val nominalKata = numberToWords(num) + " rupiah"
                return "Terimakasih, Pembayaran sejumlah $nominalKata telah diterima"
            }
        }

        // Jika tidak ada nominal yang ditemukan, tidak membaca apapun
        return ""
    }

    private fun numberToWords(n: Long): String {
        if (n == 0L) return "nol"
        val satuan = arrayOf("", "satu", "dua", "tiga", "empat", "lima",
            "enam", "tujuh", "delapan", "sembilan", "sepuluh", "sebelas")
        return when {
            n < 12 -> satuan[n.toInt()]
            n < 20 -> "${satuan[(n - 10).toInt()]} belas"
            n < 100 -> "${satuan[(n / 10).toInt()]} puluh ${numberToWords(n % 10)}".trim()
            n < 200 -> "seratus ${numberToWords(n - 100)}".trim()
            n < 1000 -> "${satuan[(n / 100).toInt()]} ratus ${numberToWords(n % 100)}".trim()
            n < 2000 -> "seribu ${numberToWords(n - 1000)}".trim()
            n < 1_000_000 -> "${numberToWords(n / 1000)} ribu ${numberToWords(n % 1000)}".trim()
            n < 1_000_000_000 -> "${numberToWords(n / 1_000_000)} juta ${numberToWords(n % 1_000_000)}".trim()
            else -> "${numberToWords(n / 1_000_000_000)} miliar ${numberToWords(n % 1_000_000_000)}".trim()
        }
    }

    fun speakText(text: String) {
        enqueueSpeak(text)
    }

    private fun enqueueSpeak(text: String) {
        if (text.isBlank()) return
        speakQueue.offer(text)
        if (!isSpeaking) processQueue()
    }

    private fun processQueue() {
        if (!ttsReady || isSpeaking) return
        val text = speakQueue.poll() ?: return
        isSpeaking = true
        val bundle = Bundle()
        bundle.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, "utt_${System.currentTimeMillis()}")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Layanan Pembaca Notifikasi",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Berjalan di latar belakang untuk membaca notifikasi GoPay Merchant"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Notifikasi Suara Aktif")
            .setContentText("Memantau pembayaran GoPay Merchant...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
