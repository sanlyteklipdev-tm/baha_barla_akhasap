package com.sanly.bahabarla.data

/** One row of the result list, already formatted for display. */
data class Product(
    val name: String,
    val code: String,
    val barcode: String,
    val price: String,
    val warehouse: String,
    val stock: String
)
