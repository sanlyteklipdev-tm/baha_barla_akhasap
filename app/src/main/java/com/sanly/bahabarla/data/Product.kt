package com.sanly.bahabarla.data

/** One row of the result list, already formatted for display. */
data class Product(
    val name: String,
    val code: String,
    val barcode: String,
    val price: String,
    /** The same price converted with today's rate; empty when no rate is on file. */
    val priceUsd: String,
    /** The rate that conversion used, for showing alongside it. */
    val rate: String,
    val warehouse: String,
    val stock: String
)
