package com.bachtiarzs.notifikasisuara

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bachtiarzs.notifikasisuara.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateStatus()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupListeners() {
        binding.btnPermission.setOnClickListener {
            if (!isNotificationListenerEnabled()) {
                showPermissionDialog()
            } else {
                Toast.makeText(this, "Izin sudah aktif!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchEnable.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            prefs.edit().putBoolean("enabled", isChecked).apply()
            val statusText = if (isChecked) "Aktif" else "Nonaktif"
            binding.tvStatus.text = "Status: $statusText"
        }

        binding.btnTestTts.setOnClickListener {
            val service = NotificationReaderService.instance
            if (service != null) {
                service.speakText("Halo! Ini adalah tes suara. GoPay Merchant menerima pembayaran sebesar lima puluh ribu rupiah.")
            } else {
                Toast.makeText(this, "Aktifkan izin notifikasi terlebih dahulu", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStatus() {
        val enabled = isNotificationListenerEnabled()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val serviceEnabled = prefs.getBoolean("enabled", true)

        if (enabled) {
            binding.tvPermissionStatus.text = "Izin Notifikasi: Aktif"
            binding.tvPermissionStatus.setTextColor(getColor(R.color.green))
            binding.btnPermission.text = "Izin Sudah Diberikan"
            binding.switchEnable.isEnabled = true
            binding.switchEnable.isChecked = serviceEnabled
            binding.cardApps.visibility = View.VISIBLE
        } else {
            binding.tvPermissionStatus.text = "Izin Notifikasi: Belum Aktif"
            binding.tvPermissionStatus.setTextColor(getColor(R.color.red))
            binding.btnPermission.text = "Aktifkan Izin Notifikasi"
            binding.switchEnable.isEnabled = false
            binding.cardApps.visibility = View.GONE
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && TextUtils.equals(pkgName, cn.packageName)) {
                    return true
                }
            }
        }
        return false
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Izin Diperlukan")
            .setMessage("Aplikasi memerlukan izin untuk membaca notifikasi. Aktifkan di pengaturan.")
            .setPositiveButton("Buka Pengaturan") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
