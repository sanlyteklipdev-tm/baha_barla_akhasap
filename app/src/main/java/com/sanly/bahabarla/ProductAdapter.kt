package com.sanly.bahabarla

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sanly.bahabarla.data.Product
import com.sanly.bahabarla.databinding.ItemProductBinding

/** The hit list a search produces; tapping a row opens its card. */
class ProductAdapter(
    private val onClick: (Product) -> Unit
) : RecyclerView.Adapter<ProductAdapter.Holder>() {

    private val items = mutableListOf<Product>()

    @Suppress("NotifyDataSetChanged") // the whole list is replaced every search
    fun submit(products: List<Product>) {
        items.clear()
        items.addAll(products)
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemProductBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(
        private val binding: ItemProductBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(product: Product) = with(binding) {
            tvRowName.text = product.name
            tvRowBarcode.text = product.barcode
            tvRowStock.text = product.stock
            root.setOnClickListener { onClick(product) }
        }
    }
}
