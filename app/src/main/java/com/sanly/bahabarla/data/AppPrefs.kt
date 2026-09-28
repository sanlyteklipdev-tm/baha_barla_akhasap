package com.sanly.bahabarla.data

import android.content.Context

/** Connection settings entered on the Settings screen. */
class AppPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        dropLegacyCredentials()
    }

    /** Address of the bridge service, e.g. `192.168.0.110:8080`. */
    val server: String get() = prefs.getString(KEY_SERVER, "").orEmpty().trim()

    /** Which akhasap database to search; the bridge decides if it is allowed. */
    val database: String get() = prefs.getString(KEY_DATABASE, "").orEmpty().trim()

    /**
     * The person's own SQL Server login. The bridge opens the database with it,
     * so what they can see is whatever SQL Server grants that login.
     */
    val userName: String get() = prefs.getString(KEY_USER, "").orEmpty()

    /** Remembered so it is not asked for on every start; kept encrypted. */
    val password: String get() = SecretBox.open(prefs.getString(KEY_PASSWORD, "").orEmpty())

    val isConfigured: Boolean
        get() = server.isNotEmpty() && database.isNotEmpty() &&
            userName.isNotEmpty() && password.isNotEmpty()

    fun save(server: String, database: String, userName: String, password: String) {
        prefs.edit()
            .putString(KEY_SERVER, server.trim())
            .putString(KEY_DATABASE, database.trim())
            .putString(KEY_USER, userName.trim())
            .putString(KEY_PASSWORD, SecretBox.seal(password))
            .apply()
    }

    /**
     * Manat per dollar, typed by hand in Settings and kept only on this phone.
     * The card divides the manat price by it; the database is never asked.
     */
    val usdRateText: String get() = prefs.getString(KEY_USD_RATE, "").orEmpty()

    /** The rate as a number, null when none is set or it is not a usable one. */
    val usdRate: Double?
        get() = usdRateText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }

    fun saveUsdRate(rate: String) {
        prefs.edit().putString(KEY_USD_RATE, rate.trim()).apply()
    }

    /**
     * The warehouse picked in the toolbar, 0 when none is. Kept per database:
     * warehouse ids of one akhasap database mean nothing in another.
     */
    val warehouseId: Int get() = prefs.getInt(KEY_WAREHOUSE_ID + database, 0)

    val warehouseName: String get() = prefs.getString(KEY_WAREHOUSE_NAME + database, "").orEmpty()

    fun saveWarehouse(warehouse: Warehouse) {
        prefs.edit()
            .putInt(KEY_WAREHOUSE_ID + database, warehouse.id)
            .putString(KEY_WAREHOUSE_NAME + database, warehouse.name)
            .apply()
    }

    /** Forgets the login but keeps the address and database, for the next person. */
    fun logOut() {
        prefs.edit()
            .remove(KEY_USER)
            .remove(KEY_PASSWORD)
            .apply()
    }

    /**
     * The first version connected to SQL Server directly and kept the shared
     * `sa` login under these keys, in plain text. Per-person logins use new
     * keys, so that copy is never picked up again and is wiped instead.
     */
    private fun dropLegacyCredentials() {
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
        const val KEY_USER = "sql_user"
        const val KEY_PASSWORD = "sql_password_sealed"
        const val KEY_USD_RATE = "usd_rate"
        const val KEY_WAREHOUSE_ID ="warehouse_id:"
        const val KEY_WAREHOUSE_NAME = "warehouse_name:"
        const val KEY_LEGACY_USER = "user_name"
        const val KEY_LEGACY_PASSWORD = "password"
    }
}
