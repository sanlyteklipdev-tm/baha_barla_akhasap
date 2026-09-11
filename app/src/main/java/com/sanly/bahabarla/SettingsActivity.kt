package com.sanly.bahabarla

import android.annotation.SuppressLint
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sanly.bahabarla.data.AppPrefs
import com.sanly.bahabarla.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.tvDeviceId.text = getString(R.string.device_id_fmt, deviceId())

        binding.etServer.setText(prefs.server)
        binding.etDatabase.setText(prefs.database)

        binding.btnSave.setOnClickListener { save() }
    }

    private fun save() {
        val server = binding.etServer.text.toString()
        val database = binding.etDatabase.text.toString()

        if (server.isBlank() || database.isBlank()) {
            Toast.makeText(this, R.string.settings_incomplete, Toast.LENGTH_LONG).show()
            return
        }
        prefs.save(server, database)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Identifies this installation to the server operator; shown for support. */
    @SuppressLint("HardwareIds")
    private fun deviceId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
}
