package com.sanly.bahabarla

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.sanly.bahabarla.data.AppPrefs
import com.sanly.bahabarla.data.Product
import com.sanly.bahabarla.data.ApiClient
import com.sanly.bahabarla.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private lateinit var adapter: ProductAdapter

    /** Kept so backing out of a card can put the list back. */
    private var results: List<Product> = emptyList()

    private val backToList = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showResults(results)
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        setSupportActionBar(binding.toolbar)

        adapter = ProductAdapter(::showProduct)
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter
        binding.rvResults.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )

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

        // A card opened from a multi-hit list backs out to that list, not out
        // of the app.
        onBackPressedDispatcher.addCallback(this, backToList)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        } else {
            super.onOptionsItemSelected(item)
        }

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
                is ApiClient.Outcome.Found -> {
                    results = outcome.products
                    // A scan, or a name that matches exactly one product, goes
                    // straight to the card — no point tapping through a list of one.
                    if (results.size == 1) showProduct(results.first()) else showResults(results)
                }
                is ApiClient.Outcome.NotFound ->
                    showMessage(getString(R.string.err_not_found))
                is ApiClient.Outcome.Failure -> showMessage(
                    when (outcome.kind) {
                        ApiClient.ErrorKind.NOT_CONFIGURED ->
                            getString(R.string.err_no_settings)
                        ApiClient.ErrorKind.CONNECT -> getString(R.string.err_connect)
                        ApiClient.ErrorKind.AUTH -> getString(R.string.err_auth)
                        ApiClient.ErrorKind.QUERY ->
                            getString(R.string.err_query, outcome.detail)
                    }
                )
            }
        }
    }

    private fun showResults(products: List<Product>) {
        adapter.submit(products)
        binding.tvResultCount.text = getString(R.string.result_count_fmt, products.size)
        binding.tvResultCount.visibility = View.VISIBLE
        binding.rvResults.visibility = View.VISIBLE
        binding.rvResults.scrollToPosition(0)
        binding.cardResult.root.visibility = View.GONE
        binding.tvMessage.visibility = View.GONE
        backToList.isEnabled = false
    }

    private fun showProduct(product: Product) {
        with(binding.cardResult) {
            tvName.text = product.name
            tvBarcode.text = product.barcode.ifEmpty { product.code }
            tvPrice.text = getString(R.string.currency_fmt, product.price)
            // No rate entered for today means no dollar price -- hide the row
            // rather than show a zero that looks like a real number.
            tvPriceUsd.text = getString(R.string.currency_usd_fmt, product.priceUsd)
            rowPriceUsd.visibility =
                if (product.priceUsd.isEmpty()) View.GONE else View.VISIBLE
            tvField1.text = product.warehouse
            // tvField2 / tvField3 stay hidden until their columns are decided.
            tvCategory.text = product.stock
            root.visibility = View.VISIBLE
        }
        binding.tvMessage.visibility = View.GONE
        binding.tvResultCount.visibility = View.GONE
        binding.rvResults.visibility = View.GONE
        backToList.isEnabled = results.size > 1
    }

    private fun showMessage(text: String) {
        binding.cardResult.root.visibility = View.GONE
        binding.tvResultCount.visibility = View.GONE
        binding.rvResults.visibility = View.GONE
        binding.tvMessage.text = text
        binding.tvMessage.visibility = View.VISIBLE
        backToList.isEnabled = false
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSearch.isEnabled = !loading
        binding.btnScan.isEnabled = !loading
        if (loading) {
            binding.tvMessage.visibility = View.GONE
            binding.cardResult.root.visibility = View.GONE
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
}
