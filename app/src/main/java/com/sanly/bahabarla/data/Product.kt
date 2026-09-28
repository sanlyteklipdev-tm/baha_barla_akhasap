package com.sanly.bahabarla.data

/** One row of the result list, already formatted for display. */
data class Product(
    val name: String,
    val code: String,
    val barcode: String,
    val price: String,
    /** Purchase price in whole manats, digits only; empty when none is on file. */
    val purchase: String,
    val stock: String,
    /** Needed to ask the bridge for the photo; the card shows nothing else with it. */
    val materialId: Int,
    /** Whether a photo is on file, so the card only asks for one that exists. */
    val hasImage: Boolean
)
