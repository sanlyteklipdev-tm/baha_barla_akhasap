package com.sanly.bahabarla.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to the Baha barla bridge service running on the office PC.
 *
 * The phone used to open a JDBC connection straight to SQL Server, but the
 * server is old enough that it only offers TLS 1.0, which Android 12+ removed.
 * The bridge sits on Windows, where that handshake still works, and hands us
 * plain JSON. It also means the database password no longer lives on phones.
 *
 * Deliberately built on HttpURLConnection and org.json: both ship with Android,
 * and this project cannot reach Maven to add an HTTP library.
 */
object ApiClient {

    private const val TAG = "ApiClient"
    private const val DEFAULT_PORT = 8080
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 20_000

    enum class ErrorKind { NOT_CONFIGURED, CONNECT, AUTH, QUERY }

    sealed interface Outcome {
        data class Found(val products: List<Product>) : Outcome
        object NotFound : Outcome
        data class Failure(val kind: ErrorKind, val detail: String) : Outcome
    }

    suspend fun search(prefs: AppPrefs, term: String): Outcome =
        withContext(Dispatchers.IO) {
            if (!prefs.isConfigured) {
                return@withContext Outcome.Failure(ErrorKind.NOT_CONFIGURED, "")
            }
            try {
                request(searchUrl(prefs, term))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "search failed", e)
                Outcome.Failure(ErrorKind.CONNECT, e.message.orEmpty())
            }
        }

    private fun request(url: URL): Outcome {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status in 200..299) parseProducts(body) else parseError(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseProducts(body: String): Outcome {
        val array = JSONArray(body)
        if (array.length() == 0) return Outcome.NotFound
        val products = buildList {
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                add(
                    Product(
                        name = row.optString("name"),
                        code = row.optString("code"),
                        barcode = row.optString("barcode"),
                        price = row.optString("price"),
                        priceUsd = row.optString("priceUsd"),
                        rate = row.optString("rate"),
                        warehouse = row.optString("warehouse"),
                        stock = row.optString("stock")
                    )
                )
            }
        }
        return Outcome.Found(products)
    }

    /** The bridge reports why it could not answer; anything else is a bad reply. */
    private fun parseError(status: Int, body: String): Outcome {
        Log.w(TAG, "bridge returned $status: $body")
        val error = runCatching { JSONObject(body) }.getOrNull()
        val kind = when (error?.optString("error")) {
            "auth" -> ErrorKind.AUTH
            "connect" -> ErrorKind.CONNECT
            "query", "database_not_allowed" -> ErrorKind.QUERY
            else -> ErrorKind.CONNECT
        }
        val detail = error?.optString("detail").orEmpty().ifEmpty { "HTTP $status" }
        return Outcome.Failure(kind, detail)
    }

    private fun searchUrl(prefs: AppPrefs, term: String) = URL(
        buildString {
            append(baseUrl(prefs.server))
            append("/search?q=").append(URLEncoder.encode(term, "UTF-8"))
            if (prefs.database.isNotEmpty()) {
                append("&db=").append(URLEncoder.encode(prefs.database, "UTF-8"))
            }
        }
    )

    /**
     * Accepts what a person is likely to type: a bare address, an address with
     * a port, or a full URL. Missing pieces get filled in.
     */
    private fun baseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        val withScheme =
            if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                trimmed
            } else {
                "http://$trimmed"
            }
        val scheme = withScheme.substringBefore("://")
        val rest = withScheme.substringAfter("://")
        val host = rest.substringBefore('/')
        val path = rest.substringAfter('/', "")

        val hostWithPort = if (host.contains(':')) host else "$host:$DEFAULT_PORT"
        return if (path.isEmpty()) "$scheme://$hostWithPort" else "$scheme://$hostWithPort/$path"
    }
}
