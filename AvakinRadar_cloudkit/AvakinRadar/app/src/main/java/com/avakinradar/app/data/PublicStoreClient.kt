package com.avakinradar.app.data

import com.avakinradar.app.model.StoreOffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class PublicStoreClient(
    private val storeUrl: String = STORE_URL,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    suspend fun fetchOffers(): List<StoreOffer> = withContext(Dispatchers.IO) { fetchOffersBlocking() }

    private fun fetchOffersBlocking(): List<StoreOffer> {
        var requestUrl = URL(storeUrl)
        require(requestUrl.protocol == "https" && requestUrl.host.equals("store.avakin.com", true)) {
            "Разрешён только официальный адрес https://store.avakin.com/."
        }

        var redirects = 0
        while (true) {
            val connection = connectionFactory(requestUrl)
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                connection.setRequestProperty("User-Agent", "AvakinRadar/1.0 (public unauthenticated storefront monitor)")
                connection.setRequestProperty("Cache-Control", "no-cache")
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirects++ >= MAX_REDIRECTS) throw IOException("Магазин перенаправил запрос слишком много раз.")
                    val location = connection.getHeaderField("Location") ?: throw IOException("Магазин вернул пустое перенаправление.")
                    val nextUrl = URL(requestUrl, location)
                    if (nextUrl.protocol != "https" || !nextUrl.host.equals("store.avakin.com", true)) {
                        throw IOException("Официальный магазин перенаправил запрос на другой адрес; мониторинг остановлен.")
                    }
                    requestUrl = nextUrl
                    continue
                }
                if (code !in 200..299) throw IOException("Официальный магазин вернул HTTP $code.")

                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8_192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_HTML_BYTES) throw IOException("Ответ магазина слишком большой для проверки.")
                        output.write(buffer, 0, count)
                    }
                }
                val html = output.toString(StandardCharsets.UTF_8.name())
                val parsed = OfferParser.parse(html, requestUrl.toString())
                if (parsed.error != null) throw IOException(parsed.error)
                return parsed.offers
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        const val STORE_URL = "https://store.avakin.com/"
        private const val MAX_HTML_BYTES = 3 * 1024 * 1024
        private const val MAX_REDIRECTS = 5
    }
}
