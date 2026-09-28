package com.sanly.bahabarla

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sanly.bahabarla.data.AppPrefs
import com.sanly.bahabarla.data.Product
import com.sanly.bahabarla.data.Warehouse
import com.sanly.bahabarla.data.ApiClient
import com.sanly.bahabarla.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private lateinit var adapter: ProductAdapter

    /** The product whose photo the camera is replacing. */
    private var photoFor: Product? = null

    /** The toolbar drop-down's label; null until the menu has been built. */
    private var warehouseLabel: TextView? = null

    private var warehouses: List<Warehouse> = emptyList()

    /** Which database and login [warehouses] were fetched for. */
    private var warehousesFor = ""

    /** Where the camera app writes its shot, kept until it has been sent on. */
    private var pendingPhoto: File? = null

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val barcode = result.data?.getStringExtra(ScannerActivity.EXTRA_BARCODE)
        if (result.resultCode == RESULT_OK && !barcode.isNullOrBlank()) {
            binding.etBarcode.setText(barcode)
            binding.etBarcode.setSelection(barcode.length)
            search()
        }
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openCamera() else toast(getString(R.string.err_camera_permission))
    }

    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { taken ->
        val file = pendingPhoto
        pendingPhoto = null
        if (taken && file != null) sendPhoto(file) else file?.delete()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        setSupportActionBar(binding.toolbar)

        // Decode photos to the size of the box on screen, not the size they were saved at.
        val photoPx = resources.getDimensionPixelSize(R.dimen.card_photo_width)
        adapter = ProductAdapter(
            scope = lifecycleScope,
            loadPhoto = { ApiClient.photo(prefs, it, photoPx) },
            usdRate = { prefs.usdRate },
            onPhotoTap = ::askForNewPhoto,
            onDoneEditing = { hideKeyboard() }
        )
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter

        binding.btnScan.setOnClickListener {
            scanLauncher.launch(Intent(this, ScannerActivity::class.java))
        }
        binding.btnSearch.setOnClickListener { search() }
        binding.etBarcode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else {
                false
            }
        }

        // Nothing can be searched without a login, and phones upgraded from the
        // version without one have none yet -- go straight to where it is typed.
        if (savedInstanceState == null && !prefs.isConfigured) openSettings()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Settings may mean another database or login, and
        // with it another set of warehouses.
        loadWarehouses()
        // The dollar rate may have been changed there too. Only then are the
        // cards redrawn, so coming back from the camera keeps typed prices.
        val rate = prefs.usdRate
        if (rate != shownRate) {
            shownRate = rate
            adapter.refresh()
        }
    }

    private var shownRate: Double? = null

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        warehouseLabel = menu.findItem(R.id.action_warehouse).actionView as TextView
        warehouseLabel?.setOnClickListener { showWarehouseMenu(it) }
        showWarehouseName()
        return true
    }

    /**
     * Fetched once per database and login. A failure leaves the last chosen
     * name in place, since searches still carry its id.
     */
    private fun loadWarehouses() {
        if (!prefs.isConfigured) {
            warehouses = emptyList()
            warehousesFor = ""
            showWarehouseName()
            return
        }
        val key = "${prefs.userName}@${prefs.server}/${prefs.database}"
        if (key == warehousesFor && warehouses.isNotEmpty()) {
            showWarehouseName()
            return
        }
        lifecycleScope.launch {
            val loaded = ApiClient.warehouses(prefs) ?: return@launch showWarehouseName()
            warehouses = loaded
            warehousesFor = key
            // First start on this database, or the saved warehouse was deleted
            // in the accounting program: fall back to the first one listed.
            if (loaded.isNotEmpty() && loaded.none { it.id == prefs.warehouseId }) {
                prefs.saveWarehouse(loaded.first())
            }
            showWarehouseName()
        }
    }

    private fun showWarehouseMenu(anchor: View) {
        if (warehouses.isEmpty()) {
            warehousesFor = ""
            loadWarehouses()
            toast(getString(R.string.err_no_warehouses))
            return
        }
        val popup = PopupMenu(this, anchor)
        warehouses.forEachIndexed { index, warehouse ->
            popup.menu.add(Menu.NONE, index, index, warehouse.name).apply {
                isCheckable = true
                isChecked = warehouse.id == prefs.warehouseId
            }
        }
        popup.menu.setGroupCheckable(Menu.NONE, true, true)
        popup.setOnMenuItemClickListener { item ->
            val chosen = warehouses[item.itemId]
            prefs.saveWarehouse(chosen)
            showWarehouseName()
            refreshForWarehouse()
            true
        }
        popup.show()
    }

    private fun showWarehouseName() {
        warehouseLabel?.text = prefs.warehouseName.ifEmpty { getString(R.string.no_warehouse) }
    }

    /** Stock on screen belonged to the previous warehouse, so the search runs again. */
    private fun refreshForWarehouse() {
        // Every pick refreshes, even of the same warehouse, and so does a pick
        // after "not found": the product may well be in the other warehouse.
        // Only an empty search box has nothing to run again.
        if (binding.etBarcode.text.isNotBlank()) search()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == R.id.action_settings) {
            openSettings()
            true
        } else {
            super.onOptionsItemSelected(item)
        }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun search() {
        val term = binding.etBarcode.text.toString().trim()
        when {
            term.isEmpty() -> {
                toast(getString(R.string.err_empty_barcode))
                return
            }
            !prefs.isConfigured -> {
                showMessage(getString(R.string.err_no_settings))
                return
            }
            !isOnline() -> {
                showMessage(getString(R.string.err_no_network))
                return
            }
        }

        hideKeyboard()
        setLoading(true)
        lifecycleScope.launch {
            val outcome = ApiClient.search(prefs, term)
            setLoading(false)
            when (outcome) {
                is ApiClient.Outcome.Found -> showResults(outcome.products)
                is ApiClient.Outcome.NotFound ->
                    showMessage(getString(R.string.err_not_found))
                is ApiClient.Outcome.Failure -> showMessage(failureText(outcome))
            }
        }
    }

    private fun showResults(products: List<Product>) {
        adapter.submit(products)
        binding.tvResultCount.text = getString(R.string.result_count_fmt, products.size)
        binding.tvResultCount.visibility = View.VISIBLE
        binding.rvResults.visibility = View.VISIBLE
        binding.rvResults.scrollToPosition(0)
        binding.tvMessage.visibility = View.GONE
    }

    private fun askForNewPhoto(product: Product) {
        AlertDialog.Builder(this)
            .setTitle(R.string.replace_photo_title)
            .setMessage(getString(R.string.replace_photo_message, product.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.replace_photo_confirm) { _, _ ->
                photoFor = product
                startCamera()
            }
            .show()
    }

    /**
     * The app declares the camera permission for the scanner, and Android then
     * insists it is granted before any camera app will hand a picture back.
     */
    private fun startCamera() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) openCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun openCamera() {
        val file = File(File(cacheDir, CAMERA_DIR).apply { mkdirs() }, CAMERA_FILE)
        pendingPhoto = file
        val uri = FileProvider.getUriForFile(this, "$packageName.photos", file)
        runCatching { takePhoto.launch(uri) }.onFailure {
            pendingPhoto = null
            toast(getString(R.string.err_camera))
        }
    }

    /**
     * Sends the shot and then puts the picture on the card up again from the
     * bridge, so what stands there afterwards is what the database now holds.
     */
    private fun sendPhoto(file: File) {
        val product = photoFor
        if (product == null) {
            file.delete()
            return
        }
        lifecycleScope.launch {
            binding.progress.visibility = View.VISIBLE
            val jpeg = withContext(Dispatchers.IO) { readForUpload(file) }
            file.delete()
            if (jpeg == null) {
                binding.progress.visibility = View.GONE
                toast(getString(R.string.err_photo_read))
                return@launch
            }

            val failure = ApiClient.replacePhoto(prefs, product, jpeg)
            binding.progress.visibility = View.GONE
            if (failure != null) {
                toast(
                    when (failure.kind) {
                        // Reading is enough for search; changing a photo needs
                        // rights on tbl_mg_images that not every login has.
                        ApiClient.ErrorKind.NO_ACCESS -> getString(R.string.err_photo_no_access)
                        ApiClient.ErrorKind.AUTH -> getString(R.string.err_auth)
                        else -> getString(R.string.err_photo_save)
                    }
                )
                return@launch
            }

            // A product that had no picture has one now; its card fetches it again.
            adapter.update(product.copy(hasImage = true))
            toast(getString(R.string.photo_saved))
        }
    }

    /**
     * Turns the full-size shot into something worth storing: turned the way the
     * phone was held, no larger than [PHOTO_UPLOAD_PX], and re-compressed. A
     * twelve-megapixel original would otherwise go into the accounting database
     * exactly as it came out of the camera.
     */
    private fun readForUpload(file: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longestSide <= 0) return null

        var sample = 1
        while (longestSide / (sample * 2) >= PHOTO_UPLOAD_PX) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        return ByteArrayOutputStream().use { out ->
            turnUpright(bitmap, file).compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, out)
            out.toByteArray()
        }
    }

    /** Phones note which way they were held rather than turning the pixels. */
    private fun turnUpright(bitmap: Bitmap, file: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.path)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun showMessage(text: String) {
        binding.tvResultCount.visibility = View.GONE
        binding.rvResults.visibility = View.GONE
        binding.tvMessage.text = text
        binding.tvMessage.visibility = View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSearch.isEnabled = !loading
        binding.btnScan.isEnabled = !loading
        if (loading) {
            binding.tvMessage.visibility = View.GONE
            binding.tvResultCount.visibility = View.GONE
            binding.rvResults.visibility = View.GONE
        }
    }

    private fun isOnline(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            manager.activeNetworkInfo?.isConnected == true
        }
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(binding.etBarcode.windowToken, 0)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val CAMERA_DIR = "camera"
        const val CAMERA_FILE = "product.jpg"

        /** Large enough to read a label on, small enough to keep in a row. */
        const val PHOTO_UPLOAD_PX = 1024
        const val PHOTO_QUALITY = 85
    }
}
