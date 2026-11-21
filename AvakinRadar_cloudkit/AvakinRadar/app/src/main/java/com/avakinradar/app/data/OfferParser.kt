package com.avakinradar.app.data

import com.avakinradar.app.model.OfferCategory
import com.avakinradar.app.model.StoreOffer
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.text.Normalizer
import java.util.Locale

data class ParseResult(val offers: List<StoreOffer>, val error: String? = null)

/** Parses only offer details present in the publicly rendered storefront HTML. */
object OfferParser {
    private const val OFFICIAL_HOST = "store.avakin.com"
    private val leadingPriceRegex = Regex("(?:(?:USD|EUR|GBP|CAD|AUD|JPY|RUB)\\s*(?:[$€£¥₽]\\s*)?|[$€£¥₽])\\s*[0-9][0-9.,]*", RegexOption.IGNORE_CASE)
    private val trailingPriceRegex = Regex("[0-9][0-9.,]*\\s*(?:US\\$|USD|EUR|GBP|CAD|AUD|JPY|RUB|€|£|¥|₽|\\$)", RegexOption.IGNORE_CASE)
    private val currencyRegex = Regex("(?i)(\\+\\s*)?([0-9][0-9,.\\s]*)\\s*(Crowns?|Avacoins?)")
    private val bonusRegex = Regex("(?i)\\+\\s*([0-9][0-9,.\\s]*)\\s*bonus")
    private val inAppRegex = Regex("(?i)In the app\\s*:\\s*([0-9][0-9,.\\s]*)")

    fun parse(html: String, pageUrl: String = "https://store.avakin.com/"): ParseResult {
        val document = Jsoup.parse(html, pageUrl)
        if (document.selectFirst("form input[name*=captcha], iframe[src*=captcha], [id*=captcha], [class*=captcha]") != null ||
            document.text().contains("verify you are human", ignoreCase = true)) {
            return ParseResult(emptyList(), "Магазин попросил пройти проверку доступа. Мониторинг не обходит её.")
        }

        val found = linkedMapOf<String, StoreOffer>()
        document.select("script[type=application/ld+json]").forEach { script ->
            runCatching { extractJsonLd(JSONObject(script.data()), pageUrl, found) }
            runCatching { extractJsonLd(JSONArray(script.data()), pageUrl, found) }
        }
        extractHtmlCards(document.select("body").firstOrNull(), pageUrl, found)

        val offers = found.values.sortedWith(compareBy<StoreOffer> { priority(it.category) }.thenBy { it.title.lowercase(Locale.ROOT) })
        if (offers.isEmpty()) {
            val message = if (document.select("script").size > 2) {
                "Магазин загрузил страницу, но его предложения не видны в публичном HTML. Снимок не обновлён, чтобы не создавать ложные события."
            } else {
                "В открытой странице магазина не найдено предложений. Проверьте соединение и повторите попытку."
            }
            return ParseResult(emptyList(), message)
        }
        return ParseResult(offers)
    }

    private fun extractHtmlCards(root: Element?, pageUrl: String, output: MutableMap<String, StoreOffer>) {
        root ?: return
        root.select("h2, h3, h4, [role=heading]").forEach { heading ->
            val title = clean(heading.text())
            if (title.length !in 2..160 || title.contains("help", true) || title.contains("featured offers", true)) return@forEach

            var card: Element? = heading.parent()
            var candidate: Element? = null
            for (depth in 0 until 5) {
                val current = card ?: break
                val text = clean(current.text())
                val headingCount = current.select("h2, h3, h4, [role=heading]").size
                if (text.length <= 600 && headingCount <= 1 && hasPrice(text)) {
                    candidate = current
                    break
                }
                card = current.parent()
            }
            val container = candidate ?: return@forEach
            val text = clean(container.text())
            val price = findPrice(text)
            val link = (heading.closest("a[href]") ?: container.selectFirst("a[href]"))?.attr("abs:href")
            val offerUrl = (if (link == null) normalizeUrl(pageUrl, pageUrl) else normalizeUrl(link, pageUrl)) ?: return@forEach
            val image = container.selectFirst("img[src], img[data-src]")?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
            val details = currencyData(text)
            val expiresAt = container.selectFirst("time[datetime], [data-end-time], [data-expiry]")?.let { element ->
                element.attr("datetime").ifBlank { element.attr("data-end-time") }.ifBlank { element.attr("data-expiry") }.takeIf(String::isNotBlank)
            }
            val offer = StoreOffer(
                id = stableId(link, title), title = title, price = price,
                priceCurrency = currencyCode(price), crowns = details.crowns,
                avacoins = details.avacoins, bonus = bonusRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNullSafe(),
                inAppAmount = inAppRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNullSafe(),
                description = text.takeIf { it != title }?.take(500), url = offerUrl,
                imageUrl = image?.takeIf { it.startsWith("https://") }, category = category(text, details.crowns, details.avacoins),
                webExclusive = text.contains("web exclusive", true), expiresAt = expiresAt
            )
            if (isRelevant(offer, text)) output.putIfAbsent(offer.id, offer)
        }
    }

    private fun extractJsonLd(value: Any?, pageUrl: String, output: MutableMap<String, StoreOffer>) {
        when (value) {
            is JSONArray -> for (index in 0 until value.length()) extractJsonLd(value.opt(index), pageUrl, output)
            is JSONObject -> {
                val type = value.opt("@type")?.toString().orEmpty()
                if (type.contains("Product", true) && value.has("name")) {
                    val title = clean(value.optString("name"))
                    val description = clean(value.optString("description")).takeIf(String::isNotBlank)
                    val offerJson = value.opt("offers")
                    val offer = if (offerJson is JSONArray) offerJson.optJSONObject(0) else offerJson as? JSONObject
                    val price = offer?.opt("price")?.toString()
                    val currency = offer?.optString("priceCurrency")?.takeIf(String::isNotBlank)
                    val url = normalizeUrl(value.optString("url").ifBlank { offer?.optString("url").orEmpty() }, pageUrl)
                    if (title.isNotBlank() && url != null) {
                        val text = listOf(title, description.orEmpty()).joinToString(" ")
                        val amounts = currencyData(text)
                        val imageValue = value.opt("image")
                        val image = when (imageValue) { is JSONArray -> imageValue.optString(0); else -> imageValue?.toString() }
                        val item = StoreOffer(
                            id = stableId(url, title), title = title, price = price,
                            priceCurrency = currency ?: currencyCode(price), crowns = amounts.crowns,
                            avacoins = amounts.avacoins, bonus = bonusRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNullSafe(),
                            inAppAmount = inAppRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNullSafe(),
                            description = description, url = url, imageUrl = normalizeImage(image, pageUrl),
                            category = category(text, amounts.crowns, amounts.avacoins),
                            webExclusive = text.contains("web exclusive", true),
                            expiresAt = offer?.optString("availabilityEnds")?.takeIf(String::isNotBlank)
                        )
                        if (isRelevant(item, text)) output.putIfAbsent(item.id, item)
                    }
                }
                value.keys().forEach { key ->
                    if (key != "offers") extractJsonLd(value.opt(key), pageUrl, output)
                }
            }
        }
    }

    private data class CurrencyData(val crowns: Int?, val avacoins: Int?)

    private fun currencyData(text: String): CurrencyData {
        var crowns: Int? = null
        var avacoins: Int? = null
        currencyRegex.findAll(text).forEach { match ->
            val amount = match.groupValues[2].toIntOrNullSafe() ?: return@forEach
            if (match.groupValues[3].startsWith("crown", true)) crowns = maxOf(crowns ?: 0, amount)
            else avacoins = maxOf(avacoins ?: 0, amount)
        }
        return CurrencyData(crowns, avacoins)
    }

    private fun category(text: String, crowns: Int?, avacoins: Int?): OfferCategory = when {
        crowns != null && avacoins != null -> OfferCategory.CURRENCY_BUNDLE
        crowns != null -> OfferCategory.CROWNS
        avacoins != null -> OfferCategory.AVACOINS
        text.contains("web exclusive", true) -> OfferCategory.WEB_EXCLUSIVE
        else -> OfferCategory.OTHER
    }

    private fun isRelevant(offer: StoreOffer, text: String): Boolean =
        offer.price != null && (offer.crowns != null || offer.avacoins != null || offer.category == OfferCategory.WEB_EXCLUSIVE || text.contains("bundle", true))

    private fun hasPrice(text: String): Boolean = leadingPriceRegex.containsMatchIn(text) || trailingPriceRegex.containsMatchIn(text)

    private fun findPrice(text: String): String? =
        (leadingPriceRegex.findAll(text).lastOrNull() ?: trailingPriceRegex.findAll(text).lastOrNull())?.value?.let(::clean)

    private fun priority(category: OfferCategory): Int = when (category) {
        OfferCategory.CURRENCY_BUNDLE -> 0
        OfferCategory.CROWNS, OfferCategory.AVACOINS -> 1
        OfferCategory.WEB_EXCLUSIVE -> 2
        OfferCategory.OTHER -> 3
    }

    private fun stableId(rawUrl: String?, title: String): String {
        val normalizedUrl = rawUrl.orEmpty().substringBefore('?').substringBefore('#').trimEnd('/')
        val path = runCatching { URI(normalizedUrl).path.orEmpty().trim('/') }.getOrDefault("")
        val pathSegments = path.split('/').filter(String::isNotBlank)
        val localeOnly = pathSegments.singleOrNull()?.matches(Regex("(?i)[a-z]{2}-[a-z]{2}")) == true
        val key = if (normalizedUrl.isNotBlank() && pathSegments.isNotEmpty() && !localeOnly) normalizedUrl
        else Normalizer.normalize(title, Normalizer.Form.NFKD)
        return key.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '/' || it == '-' }.takeLast(180)
    }

    private fun normalizeUrl(raw: String?, base: String): String? = runCatching {
        if (raw.isNullOrBlank()) return null
        val uri = URI(base).resolve(raw)
        if (uri.scheme != "https" || uri.host?.equals(OFFICIAL_HOST, ignoreCase = true) != true) return null
        URI("https", null, OFFICIAL_HOST, uri.port, uri.path ?: "/", uri.query, null).toString()
    }.getOrNull()

    private fun normalizeImage(raw: String?, base: String): String? = runCatching {
        if (raw.isNullOrBlank()) return null
        val uri = URI(base).resolve(raw)
        if (uri.scheme == "https") uri.toString() else null
    }.getOrNull()

    private fun currencyCode(price: String?): String? = when {
        price == null -> null
        price.contains('$') -> "USD"
        price.contains('€') -> "EUR"
        price.contains('£') -> "GBP"
        price.contains('¥') -> "JPY"
        price.contains('₽') -> "RUB"
        else -> Regex("(?i)\\b(USD|EUR|GBP|CAD|AUD|JPY|RUB)\\b").find(price)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
    }

    private fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim()
    private fun String.toIntOrNullSafe(): Int? = filter(Char::isDigit).toIntOrNull()
}
