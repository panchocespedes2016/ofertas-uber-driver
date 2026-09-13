package com.eu.ofertasevidencia

import java.security.MessageDigest
import java.util.Locale

object OfferAnalyzer {
    private val offerWords = listOf(
        "aceptar", "rechazar", "oferta", "viaje", "recogida", "destino", "min", "km",
        "accept", "decline", "request", "trip", "pickup", "dropoff", "mile", "mi"
    )
    private val money = Regex("(?:[$€£]\\s?\\d+(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?\\s?(?:usd|eur|gbp))", RegexOption.IGNORE_CASE)
    private val distance = Regex("\\b\\d+(?:[.,]\\d+)?\\s?(?:km|mi|millas?|miles?)\\b", RegexOption.IGNORE_CASE)

    data class Result(val isOffer: Boolean, val normalized: String, val summary: String, val hash: String)

    fun analyze(parts: List<String>): Result {
        val normalized = parts.asSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" | ")
            .take(12_000)
        val lower = normalized.lowercase(Locale.ROOT)
        val keywordScore = offerWords.count { Regex("(^|[^a-záéíóúüñ])${Regex.escape(it)}([^a-záéíóúüñ]|$)").containsMatchIn(lower) }
        val hasAction = listOf("aceptar", "rechazar", "accept", "decline").any { lower.contains(it) }
        val hasMoney = money.containsMatchIn(lower)
        val hasDistance = distance.containsMatchIn(lower)
        val score = keywordScore + (if (hasAction) 2 else 0) + (if (hasMoney) 2 else 0) + (if (hasDistance) 1 else 0)
        val isOffer = normalized.length >= 15 && hasAction && score >= 4
        return Result(isOffer, normalized, normalized.take(220), sha256(normalized.toByteArray()))
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
