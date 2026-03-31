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

        // Daftar aplikasi yang ingin dibaca notifikasinya
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
                // Coba gunakan bahasa Indonesia untuk suara lebih natural
                val result = tts?.setLanguage(Locale("id", "ID"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }

                // Tuning TTS agar tidak kaku seperti robot
                tts?.setSpeechRate(0.88f)   // Lebih lambat sedikit = lebih natural
                tts?.setPitch(1.05f)         // Sedikit lebih tinggi = lebih ekspresif

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

        // Cek apakah paket ada di daftar target
        val isTarget = TARGET_APPS.any { pkg.contains(it, ignoreCase = true) } ||
            pkg.contains("gopay", ignoreCase = true) ||
            pkg.contains("gojek", ignoreCase = true)

        if (!isTarget) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val bigText = extras.getString(Notification.EXTRA_BIG_TEXT) ?: text

        Log.d(TAG, "Notifikasi dari: $pkg | $title | $text")

        if (title.isBlank() && text.isBlank()) return

        val speechText = buildNaturalSpeech(pkg, title, bigText.ifBlank { text })
        enqueueSpeak(speechText)
    }

    private fun buildNaturalSpeech(pkg: String, title: String, text: String): String {
        // Bersihkan teks dari karakter aneh
        val cleanTitle = cleanText(title)
        val cleanText = cleanText(text)

        // Deteksi nominal uang dan format dengan lebih natural
        val processedText = formatCurrencyNatural(cleanText)

        return when {
            cleanTitle.isNotBlank() && processedText.isNotBlank() -> {
                "$cleanTitle. $processedText"
            }
            cleanTitle.isNotBlank() -> cleanTitle
            else -> processedText
        }
    }

    private fun cleanText(text: String): String {
        return text
            .replace(Regex("[\\p{So}\\p{Sm}]"), "")  // hapus emoji/simbol
            .replace("\n", ". ")
            .replace("  ", " ")
            .trim()
    }

    private fun formatCurrencyNatural(text: String): String {
        // Ganti format Rp 50.000 menjadi "lima puluh ribu rupiah"
        var result = text
        val pattern = Pattern.compile("Rp[.\\s]?([\\d.,]+)")
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            val numStr = matcher.group(1)?.replace(".", "")?.replace(",", "") ?: continue
            val num = numStr.toLongOrNull() ?: continue
            val spoken = numberToWords(num)
            result = result.replace(matcher.group(0) ?: "", "$spoken rupiah")
        }
        return result
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
        channel.description = "Berjalan di latar belakang untuk membaca notifikasi"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Notifikasi Suara Aktif")
            .setContentText("Memantau notifikasi GoPay Merchant...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
