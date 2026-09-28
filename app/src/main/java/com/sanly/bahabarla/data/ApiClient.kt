package com.sanly.bahabarla.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.util.LruCache
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
 * plain JSON.
 *
 * Every request carries the person's own SQL Server login as HTTP Basic, and
 * the bridge opens the database with it -- SQL Server decides what they see.
 *
 * Deliberately built on HttpURLConnection and org.json: both ship with Android,
 * and this project cannot reach Maven to add an HTTP library.
 */
object ApiClient {

    private const val TAG = "ApiClient"
    private const val DEFAULT_PORT = 8080
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val PHOTO_CACHE_BYTES = 6 * 1024 * 1024

    /**
     * Backing out of a card and opening it again is common, and a photo costs a
     * few hundred kilobytes over the shop wifi, so decoded ones are kept.
     */
    private val photoCache = object : LruCache<String, Bitmap>(PHOTO_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** AUTH is a wrong login or password; NO_ACCESS a login SQL Server keeps out of the database. */
    enum class ErrorKind { NOT_CONFIGURED, CONNECT, AUTH, NO_ACCESS, QUERY }

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
                val (status, body) = get(searchUrl(prefs, term), authHeader(prefs))
                if (status in 200..299) parseProducts(body) else parseError(status, body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "search failed", e)
                Outcome.Failure(ErrorKind.CONNECT, e.message.orEmpty())
            }
        }

    /** The warehouses for the toolbar drop-down; null when they could not be fetched. */
    suspend fun warehouses(prefs: AppPrefs): List<Warehouse>? =
        withContext(Dispatchers.IO) {
            if (!prefs.isConfigured) return@withContext null
            try {
                val url = URL(
                    baseUrl(prefs.server) + "/warehouses?db=" +
                        URLEncoder.encode(prefs.database, "UTF-8")
                )
                val (status, body) = get(url, authHeader(prefs))
                if (status !in 200..299) {
                    Log.w(TAG, "warehouses returned $status: $body")
                    return@withContext null
                }
                val array = JSONArray(body)
                List(array.length()) { i ->
                    val row = array.getJSONObject(i)
                    Warehouse(id = row.optInt("id"), name = row.optString("name"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "warehouses failed", e)
                null
            }
        }

    /**
     * Tries a login before Settings stores it, so a mistyped password is caught
     * on the spot rather than on the first search. Null means it works.
     */
    suspend fun checkLogin(
        server: String,
        database: String,
        userName: String,
        password: String
    ): Outcome.Failure? = withContext(Dispatchers.IO) {
        try {
            val url = URL(
                baseUrl(server) + "/login?db=" + URLEncoder.encode(database.trim(), "UTF-8")
            )
            val (status, body) = get(url, authHeader(userName.trim(), password))
            if (status in 200..299) null else parseError(status, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "login check failed", e)
            Outcome.Failure(ErrorKind.CONNECT, e.message.orEmpty())
        }
    }

    /**
     * Fetches the product photo. A card without a picture is ordinary, so
     * anything that goes wrong -- no photo on file, bridge unreachable, a blob
     * that is not an image -- comes back as null and the card keeps its
     * placeholder instead of showing an error.
     */
    suspend fun photo(prefs: AppPrefs, product: Product, maxPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            if (!prefs.isConfigured || !product.hasImage || product.materialId <= 0) {
                return@withContext null
            }
            val key = photoKey(prefs, product)
            photoCache.get(key)?.let { return@withContext it }
            try {
                val bytes = download(imageUrl(prefs, product.materialId), authHeader(prefs))
                    ?: return@withContext null
                decodeScaled(bytes, maxPx)?.also { photoCache.put(key, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "photo for material ${product.materialId} failed", e)
                null
            }
        }

    /**
     * Puts a photo taken on the phone against the product. The bridge writes it
     * into the accounting database, so the desktop program sees it too. Null
     * means it was saved; a login with read rights only comes back NO_ACCESS.
     */
    suspend fun replacePhoto(prefs: AppPrefs, product: Product, jpeg: ByteArray): Outcome.Failure? =
        withContext(Dispatchers.IO) {
            if (!prefs.isConfigured) {
                return@withContext Outcome.Failure(ErrorKind.NOT_CONFIGURED, "")
            }
            if (product.materialId <= 0) {
                return@withContext Outcome.Failure(ErrorKind.QUERY, "no material id")
            }
            try {
                val (status, body) =
                    upload(imageUrl(prefs, product.materialId), authHeader(prefs), jpeg)
                if (status !in 200..299) return@withContext parseError(status, body)
                // The old picture is still in the cache and would be handed
                // back on the next look; drop it so the new one is fetched.
                photoCache.remove(photoKey(prefs, product))
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "photo upload for material ${product.materialId} failed", e)
                Outcome.Failure(ErrorKind.CONNECT, e.message.orEmpty())
            }
        }

    /** Status and body of a JSON request; the body is the error text when it failed. */
    private fun get(url: URL, auth: String): Pair<Int, String> {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", auth)
        }
        return try {
            val status = connection.responseCode
            status to readBody(connection, status)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
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
                        purchase = row.optString("purchase"),
                        stock = row.optString("stock"),
                        materialId = row.optInt("materialId"),
                        hasImage = row.optBoolean("hasImage")
                    )
                )
            }
        }
        return Outcome.Found(products)
    }

    private fun download(url: URL, auth: String): ByteArray? {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "image/*")
            setRequestProperty("Authorization", auth)
        }
        return try {
            val status = connection.responseCode
            if (status in 200..299) {
                connection.inputStream.use { it.readBytes() }
            } else {
                // 404 only means this material has no picture on file.
                if (status != HttpURLConnection.HTTP_NOT_FOUND) {
                    Log.w(TAG, "image request returned $status")
                }
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun upload(url: URL, auth: String, body: ByteArray): Pair<Int, String> {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "image/jpeg")
            setRequestProperty("Authorization", auth)
            setFixedLengthStreamingMode(body.size)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            status to readBody(connection, status)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Photos leave the accounting program at whatever size was pasted in, some
     * of them several hundred kilobytes. Only a thumbnail is ever on screen, so
     * the bitmap is shrunk while it is decoded rather than afterwards.
     */
    private fun decodeScaled(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longestSide <= 0) return null

        var sample = 1
        while (maxPx > 0 && longestSide / (sample * 2) >= maxPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** The bridge reports why it could not answer; anything else is a bad reply. */
    private fun parseError(status: Int, body: String): Outcome.Failure {
        Log.w(TAG, "bridge returned $status: $body")
        val error = runCatching { JSONObject(body) }.getOrNull()
        val kind = when (error?.optString("error")) {
            "auth" -> ErrorKind.AUTH
            "no_access" -> ErrorKind.NO_ACCESS
            "connect" -> ErrorKind.CONNECT
            "query", "database_not_allowed" -> ErrorKind.QUERY
            else -> ErrorKind.CONNECT
        }
        val detail = error?.optString("detail").orEmpty().ifEmpty { "HTTP $status" }
        return Outcome.Failure(kind, detail)
    }

    /**
     * Per login as well as per database: after someone logs out, the next
     * person must not be handed pictures their own login could not fetch.
     */
    private fun photoKey(prefs: AppPrefs, product: Product) =
        "${prefs.userName}@${prefs.database}#${product.materialId}"

    private fun authHeader(prefs: AppPrefs) = authHeader(prefs.userName, prefs.password)

    /** HTTP Basic, in UTF-8 so a password with Turkmen or Russian letters survives. */
    private fun authHeader(userName: String, password: String): String {
        val pair = "$userName:$password".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(pair, Base64.NO_WRAP)
    }

    private fun searchUrl(prefs: AppPrefs, term: String) = URL(
        buildString {
            append(baseUrl(prefs.server))
            append("/search?q=").append(URLEncoder.encode(term, "UTF-8"))
            if (prefs.database.isNotEmpty()) {
                append("&db=").append(URLEncoder.encode(prefs.database, "UTF-8"))
            }
            // Stock is the chosen warehouse's; without one the bridge shows the fullest.
            if (prefs.warehouseId != 0) append("&wh=").append(prefs.warehouseId)
        }
    )

    private fun imageUrl(prefs: AppPrefs, materialId: Int) = URL(
        buildString {
            append(baseUrl(prefs.server))
            append("/image?id=").append(materialId)
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
