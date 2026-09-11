package com.sanly.bahabarla.data

import android.content.Context

/** Connection settings entered on the Settings screen. */
class AppPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        dropDatabaseCredentials()
    }

    /** Address of the bridge service, e.g. `192.168.0.136:8080`. */
    val server: String get() = prefs.getString(KEY_SERVER, "").orEmpty().trim()

    /** Which akhasap database to search; the bridge decides if it is allowed. */
    val database: String get() = prefs.getString(KEY_DATABASE, "").orEmpty().trim()

    val isConfigured: Boolean
        get() = server.isNotEmpty() && database.isNotEmpty()

    fun save(server: String, database: String) {
        prefs.edit()
            .putString(KEY_SERVER, server.trim())
            .putString(KEY_DATABASE, database.trim())
            .apply()
    }

    /**
     * Earlier versions connected to SQL Server directly and kept the login on
     * the device. The bridge holds those now, so any copy left behind from an
     * upgrade is wiped the first time this class is constructed.
     */
    private fun dropDatabaseCredentials() {
        if (!prefs.contains(KEY_LEGACY_USER) && !prefs.contains(KEY_LEGACY_PASSWORD)) return
        prefs.edit()
            .remove(KEY_LEGACY_USER)
            .remove(KEY_LEGACY_PASSWORD)
            .apply()
    }

    private companion object {
        const val FILE = "baha_barla_prefs"
        const val KEY_SERVER = "server_address"
        const val KEY_DATABASE = "database_name"
        const val KEY_LEGACY_USER = "user_name"
        const val KEY_LEGACY_PASSWORD = "password"
    }
}
