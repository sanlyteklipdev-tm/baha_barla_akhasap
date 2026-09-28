package com.sanly.bahabarla

import android.graphics.Bitmap
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.sanly.bahabarla.data.Product
import com.sanly.bahabarla.databinding.ViewProductCardBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.random.Random

/**
 * A search goes straight to the full cards, one under another -- there is no
 * list of names to tap through first. Photos are fetched as a card scrolls
 * into view, not all fifty at once.
 */
class ProductAdapter(
    private val scope: CoroutineScope,
    private val loadPhoto: suspend (Product) -> Bitmap?,
    /** Manat per dollar from Settings; null hides the dollar price. */
    private val usdRate: () -> Double?,
    private val onPhotoTap: (Product) -> Unit,
    private val onDoneEditing: (View) -> Unit
) : RecyclerView.Adapter<ProductAdapter.Holder>() {

    private val items = mutableListOf<Product>()

    /**
     * The disguised purchase price, made once per search so a card does not
     * change its code every time it scrolls back into view.
     */
    private val purchaseCodes = mutableMapOf<Int, String>()

    @Suppress("NotifyDataSetChanged") // the whole list is replaced every search
    fun submit(products: List<Product>) {
        items.clear()
        items.addAll(products)
        purchaseCodes.clear()
        products.forEach { purchaseCodes[it.materialId] = disguisePurchase(it.purchase) }
        notifyDataSetChanged()
    }

    /** Redraws every card, e.g. after the rate was changed in Settings. */
    @Suppress("NotifyDataSetChanged")
    fun refresh() = notifyDataSetChanged()

    /** Puts a changed product (a new photo) back in place of the old one. */
    fun update(product: Product) {
        val index = items.indexOfFirst { it.materialId == product.materialId }
        if (index < 0) return
        items[index] = product
        notifyItemChanged(index)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ViewProductCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        (binding.root.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin =
            parent.resources.getDimensionPixelSize(R.dimen.card_gap)
        binding.root.visibility = View.VISIBLE
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun onViewRecycled(holder: Holder) = holder.unbind()

    inner class Holder(
        private val binding: ViewProductCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var product: Product? = null
        private var photoJob: Job? = null

        /**
         * True while one box is being written from code -- filling the card, or
         * converting what was typed into the other box. Without it the two
         * boxes would keep converting each other, and rounding to cents would
         * turn 676,00 into 676,07 on the way back.
         */
        private var filling = false

        init {
            // Both prices can be typed over, so a price can be tried out on the
            // spot: manat converts to dollars and dollars to manat at the rate
            // typed in Settings. Nothing is written to the database.
            binding.etPrice.addTextChangedListener(afterChange { showDollarsForTypedManat() })
            binding.etPriceUsd.addTextChangedListener(afterChange { showManatForTypedDollars() })

            // What the customer sees has to be a price, not a form being filled
            // in: the boxes carry no frame or caret, and Done puts the keyboard
            // and the focus away.
            listOf(binding.etPrice, binding.etPriceUsd).forEach { box ->
                box.setOnEditorActionListener { view, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        view.clearFocus()
                        binding.root.requestFocus()
                        onDoneEditing(view)
                        true
                    } else {
                        false
                    }
                }
            }
            // Tapping the photo replaces it with one taken now.
            binding.ivPhoto.setOnClickListener { product?.let(onPhotoTap) }
        }

        fun bind(item: Product) = with(binding) {
            product = item
            tvName.text = item.name
            tvBarcode.text = item.barcode.ifEmpty { item.code }
            // No rate in Settings means no dollar price -- hide the row rather
            // than show a zero that looks like a real number.
            val dollars = dollarsFor(item.price)
            filling = true
            etPrice.setText(item.price)
            etPriceUsd.setText(dollars.orEmpty())
            filling = false
            etPrice.clearFocus()
            etPriceUsd.clearFocus()
            rowPriceUsd.visibility = if (dollars == null) View.GONE else View.VISIBLE
            // The warehouse is not repeated here: the toolbar already names it.
            tvCategory.text = item.stock
            rowPurchase.visibility = if (item.purchase.isEmpty()) View.GONE else View.VISIBLE
            tvPurchase.text = purchaseCodes[item.materialId] ?: disguisePurchase(item.purchase)
            showPhoto(item)
        }

        fun unbind() {
            photoJob?.cancel()
            product = null
        }

        /**
         * The card goes up at once with the placeholder and the picture drops
         * into it when it arrives. A recycled card cancels its download, so a
         * slow photo cannot land on another product.
         */
        private fun showPhoto(item: Product) {
            photoJob?.cancel()
            val photo = binding.ivPhoto
            photo.scaleType = ImageView.ScaleType.CENTER
            photo.setImageResource(R.drawable.ic_photo_placeholder)
            if (!item.hasImage) return
            photoJob = scope.launch {
                val bitmap = loadPhoto(item) ?: return@launch
                if (product?.materialId != item.materialId) return@launch
                photo.scaleType = ImageView.ScaleType.CENTER_CROP
                photo.setImageBitmap(bitmap)
            }
        }

        /**
         * An empty or half-typed number puts the database price back, so the
         * card never shows a figure that means nothing.
         */
        private fun showManatForTypedDollars() {
            if (filling) return
            val item = product ?: return
            val rate = usdRate()
            val dollars = parseNumber(binding.etPriceUsd.text.toString())
            val manat = if (rate == null || dollars == null) item.price else money(dollars * rate)
            writeQuietly { binding.etPrice.setText(manat) }
        }

        /** The same the other way: typed manat shown in dollars. */
        private fun showDollarsForTypedManat() {
            if (filling) return
            val item = product ?: return
            val dollars = dollarsFor(binding.etPrice.text.toString()) ?: dollarsFor(item.price) ?: return
            writeQuietly { binding.etPriceUsd.setText(dollars) }
        }

        /** Manat divided by the Settings rate; null without a rate or a number. */
        private fun dollarsFor(manat: String): String? {
            val rate = usdRate() ?: return null
            val value = parseNumber(manat) ?: return null
            return money(value / rate)
        }

        private fun writeQuietly(write: () -> Unit) {
            filling = true
            write()
            filling = false
        }
    }

    private companion object {
        fun money(value: Double): String = String.format(Locale.GERMANY, "%,.2f", value)

        fun afterChange(action: () -> Unit) = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = action()
        }

        /**
         * The purchase price is for staff only, so it goes on screen behind
         * three random digits and a random letter: "384K1250". Staff read what
         * follows the letter. I and O are left out so the letter is never
         * mistaken for a digit.
         */
        fun disguisePurchase(manats: String): String {
            val digits = (1..3).map { Random.nextInt(10) }.joinToString("")
            val letter = "ABCDEFGHJKLMNPQRSTUVWXYZ".random()
            return "$digits$letter$manats"
        }

        /**
         * Reads both shapes that reach the card: 1.170,00 as the bridge formats
         * it, and 1170.56 as the numeric keypad types it.
         */
        fun parseNumber(raw: String): Double? {
            val cleaned = raw.trim().replace(" ", "").replace(" ", "")
            if (cleaned.isEmpty()) return null
            val normalised =
                if (cleaned.contains(',')) cleaned.replace(".", "").replace(',', '.') else cleaned
            return normalised.toDoubleOrNull()
        }
    }
}
