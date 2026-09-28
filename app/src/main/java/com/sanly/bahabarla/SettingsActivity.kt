package com.sanly.bahabarla

import android.annotation.SuppressLint
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sanly.bahabarla.data.ApiClient
import com.sanly.bahabarla.data.AppPrefs
import com.sanly.bahabarla.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

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
        binding.etUser.setText(prefs.userName)
        binding.etPassword.setText(prefs.password)
        binding.etUsdRate.setText(prefs.usdRateText)
        showLogOut()

        binding.btnSave.setOnClickListener { save() }
        binding.btnLogOut.setOnClickListener { logOut() }
    }

    /**
     * Tries the login against the bridge before storing it. A wrong password
     * or a database the login may not open is refused here, where it can be
     * fixed. An unreachable PC is not the login's fault, so that is saved
     * anyway and said so.
     */
    private fun save() {
        val server = binding.etServer.text.toString()
        val database = binding.etDatabase.text.toString()
        val user = binding.etUser.text.toString()
        val password = binding.etPassword.text.toString()

        // The rate has nothing to do with the login, so it is kept whether or
        // not the login below turns out to be right.
        prefs.saveUsdRate(binding.etUsdRate.text.toString())

        if (server.isBlank() || database.isBlank() || user.isBlank() || password.isEmpty()) {
            Toast.makeText(this, R.string.settings_incomplete, Toast.LENGTH_LONG).show()
            return
        }

        setChecking(true)
        lifecycleScope.launch {
            val failure = ApiClient.checkLogin(server, database, user, password)
            setChecking(false)
            when (failure?.kind) {
                null -> {
                    prefs.save(server, database, user, password)
                    Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                    finish()
                }
                ApiClient.ErrorKind.CONNECT -> {
                    prefs.save(server, database, user, password)
                    Toast.makeText(
                        this@SettingsActivity, R.string.settings_saved_unchecked, Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
                else -> Toast.makeText(
                    this@SettingsActivity, failureText(failure), Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun logOut() {
        prefs.logOut()
        binding.etUser.text.clear()
        binding.etPassword.text.clear()
        showLogOut()
        binding.etUser.requestFocus()
        Toast.makeText(this, R.string.logged_out, Toast.LENGTH_SHORT).show()
    }

    private fun showLogOut() {
        binding.btnLogOut.visibility = if (prefs.userName.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setChecking(checking: Boolean) {
        binding.btnSave.isEnabled = !checking
        binding.btnSave.setText(if (checking) R.string.checking_login else R.string.save_settings)
    }

    /** Identifies this installation to the server operator; shown for support. */
    @SuppressLint("HardwareIds")
    private fun deviceId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
}
