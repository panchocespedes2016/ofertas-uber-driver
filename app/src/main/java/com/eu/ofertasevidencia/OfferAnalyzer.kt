package com.eu.ofertasevidencia

import java.security.MessageDigest
import java.util.Locale

object OfferAnalyzer {
    private val offerWords = listOf(
        "aceptar", "rechazar", "oferta", "viaje", "recogida", "destino", "min", "km",
        "accept", "decline", "request", "trip", "pickup", "dropoff", "mile", "mi",
        "match", "exclusive", "verified", "destination", "perk"
    )
    private val money = Regex("(?:[$€£]\\s?\\d+(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?\\s?(?:usd|eur|gbp))", RegexOption.IGNORE_CASE)
    private val distance = Regex("\\b\\d+(?:[.,]\\d+)?\\s?(?:km|mi|millas?|miles?)\\b", RegexOption.IGNORE_CASE)
    private val time = Regex("\\b\\d+(?:[.,]\\d+)?\\s?(?:h|hr|hrs|horas?|min(?:s|utos?)?)\\b", RegexOption.IGNORE_CASE)

    data class Result(
        val isOffer: Boolean,
        val normalized: String,
        val summary: String,
        val hash: String,
        val price: Double? = null,
        /** Suma de todos los tiempos (recogida + viaje) en minutos. */
        val totalMinutes: Double? = null,
        /** Suma de las dos distancias que muestra Uber (recogida + viaje) en millas. */
        val totalMiles: Double? = null
    )

    fun analyze(parts: List<String>): Result {
        val normalized = parts.asSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" | ")
            .take(12_000)
        val lower = normalized.lowercase(Locale.ROOT)
        val keywordScore = offerWords.count { Regex("(^|[^a-záéíóúüñ])${Regex.escape(it)}([^a-záéíóúüñ]|$)").containsMatchIn(lower) }
        val hasAction = listOf("aceptar", "rechazar", "accept", "decline", "match").any { lower.contains(it) }
        val hasMoney = money.containsMatchIn(lower)
        val hasDistance = distance.containsMatchIn(lower)
        val score = keywordScore + (if (hasAction) 2 else 0) + (if (hasMoney) 2 else 0) + (if (hasDistance) 1 else 0)
        val isOffer = normalized.length >= 15 && hasAction && score >= 4
        val price = money.find(lower)?.value?.let(::parseNumber)
        val miles = distance.findAll(lower).mapNotNull { m ->
            val num = parseNumber(m.value) ?: return@mapNotNull null
            if (m.value.contains("km", ignoreCase = true)) num * 0.621371 else num
        }.take(2).toList()
        val minutes = time.findAll(lower).mapNotNull { m ->
            val num = parseNumber(m.value) ?: return@mapNotNull null
            val unit = m.value.trim().substringAfterLast(' ')
            if (unit.startsWith("h", ignoreCase = true)) num * 60 else num
        }.toList()
        return Result(
            isOffer, normalized, normalized.take(220), sha256(normalized.toByteArray()),
            price,
            minutes.takeIf { it.isNotEmpty() }?.sum(),
            miles.takeIf { it.isNotEmpty() }?.sum()
        )
    }

    /** True si el texto del OCR indica una oferta "exclusive" de UberX (cuadrado azul). */
    fun isExclusive(text: String): Boolean =
        Regex("\\bexclusive\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** Extrae el número de un texto como "$12.50", "12,50", "10.2 mi" o "25 min". */
    private fun parseNumber(raw: String): Double? {
        val cleaned = raw.filter { it.isDigit() || it == '.' || it == ',' }
        if (cleaned.isEmpty()) return null
        val normalized = if (cleaned.contains('.') && cleaned.contains(',')) cleaned.replace(",", "")
        else cleaned.replace(',', '.')
        return normalized.toDoubleOrNull()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
