package com.eu.ofertasevidencia

data class OfferRecord(
    val id: Long,
    val capturedAt: Long,
    val packageName: String,
    val summary: String,
    val rawText: String,
    val screenshotPath: String,
    val screenshotSha256: String,
    val textSha256: String,
    val automatic: Boolean,
    val offerKey: String
)
