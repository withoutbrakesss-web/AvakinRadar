package com.avakinradar.app.data

import android.content.Context
import android.os.SystemClock
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.avakinradar.app.model.StoreOffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reads only the anonymous public storefront and logs each observable stage without page content. */
class RenderedStoreClient(context: Context) {
    private val appContext = context.applicationContext

    suspend fun fetchOffers(onEvent: (String, String) -> Unit = { _, _ -> }): List<StoreOffer> = try {
        withTimeout(TIMEOUT_MS) {
            val userAgent = WebSettings.getDefaultUserAgent(appContext)
                .replace("; wv", "", ignoreCase = true)
                .replace("Version/4.0 ", "", ignoreCase = true)
            onEvent("request_sent", "Отправляю анонимный GET на официальный адрес магазина.")
            val response = probePublicPage(userAgent)
            val size = response.contentLength.takeIf { it >= 0 }?.let { "$it байт" } ?: "размер не указан"
            onEvent("http_response", "Пробный HTTP-ответ: ${response.statusCode}, ${response.contentType}, $size.")

            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    val webView = WebView(appContext)
                    val finished = AtomicBoolean(false)
                    val startedAt = SystemClock.elapsedRealtime()

                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                        webView.destroy()
                        continuation.resumeWithException(IOException("Встроенный Android System WebView не поддерживает отдельный публичный профиль. Обновите Android System WebView через Google Play."))
                        return@suspendCancellableCoroutine
                    }
                    runCatching { WebViewCompat.setProfile(webView, "avakin-radar-public") }
                        .onFailure {
                            webView.destroy()
                            continuation.resumeWithException(IOException("Не удалось открыть изолированный профиль для публичной страницы."))
                            return@suspendCancellableCoroutine
                        }

                    fun finish(offers: List<StoreOffer>?, error: Throwable?) {
                        if (!finished.compareAndSet(false, true)) return
                        webView.stopLoading()
                        webView.webViewClient = WebViewClient()
                        webView.destroy()
                        if (!continuation.isActive) return
                        if (error != null) continuation.resumeWithException(error)
                        else continuation.resume(offers.orEmpty())
                    }

                    fun inspectRenderedPage() {
                        if (!continuation.isActive || finished.get()) return
                        webView.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''") { encoded ->
                            if (!continuation.isActive || finished.get()) return@evaluateJavascript
                            val html = runCatching { JSONArray("[$encoded]").getString(0) }.getOrNull().orEmpty()
                            val parsed = OfferParser.parse(html, webView.url ?: PublicStoreClient.STORE_URL)
                            if (parsed.offers.isNotEmpty()) {
                                onEvent("cards_parsed", "В отрисованном DOM: ${parsed.candidateCardCount} карточек avsp-card; парсер распознал ${parsed.offers.size} предложений.")
                                finish(parsed.offers, null)
                            } else if (SystemClock.elapsedRealtime() - startedAt >= RENDER_WAIT_MS) {
                                val message = "Страница открылась, но каталог не распознан: DOM-карточек ${parsed.candidateCardCount}, предложений ${parsed.offers.size}. ${parsed.error.orEmpty()}"
                                onEvent("parse_failed", message)
                                finish(null, IOException(message))
                            } else {
                                webView.postDelayed(::inspectRenderedPage, POLL_INTERVAL_MS)
                            }
                        }
                    }

                    WebViewCompat.getProfile(webView).cookieManager.setAcceptCookie(false)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.userAgentString = userAgent
                    webView.settings.setSupportZoom(true)
                    webView.settings.builtInZoomControls = false
                    webView.settings.useWideViewPort = true
                    webView.settings.loadWithOverviewMode = false
                    webView.settings.allowFileAccess = false
                    webView.settings.allowContentAccess = false
                    webView.settings.textZoom = 100
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val uri = request?.url ?: return true
                            if (!request.isForMainFrame) return false
                            val allowed = uri.scheme.equals("https", true) &&
                                uri.host.equals("store.avakin.com", true) &&
                                (uri.port == -1 || uri.port == 443)
                            if (allowed) return false
                            val message = "Переход главного документа за пределы официального HTTPS-магазина заблокирован."
                            onEvent("navigation_blocked", message)
                            finish(null, IOException(message))
                            return true
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            if (url != null) {
                                val uri = runCatching { java.net.URI(url) }.getOrNull()
                                val allowed = uri?.scheme.equals("https", true) &&
                                    uri?.host.equals("store.avakin.com", true) &&
                                    (uri?.port == -1 || uri?.port == 443)
                                if (!allowed) {
                                    val message = "WebView начал переход за пределы официального HTTPS-магазина; загрузка остановлена."
                                    onEvent("navigation_blocked", message)
                                    finish(null, IOException(message))
                                    return
                                }
                            }
                            onEvent("webview_started", "Android WebView начал загрузку публичной страницы.")
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url?.startsWith("https://store.avakin.com/", ignoreCase = true) == true) {
                                onEvent("webview_loaded", "Главный документ магазина загружен в WebView; жду отрисовку JavaScript-каталога.")
                                view?.postDelayed(::inspectRenderedPage, INITIAL_RENDER_WAIT_MS)
                            }
                        }

                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                            if (request?.isForMainFrame == true) {
                                val message = "WebView получил HTTP ${errorResponse?.statusCode ?: "ошибку"} при загрузке каталога."
                                onEvent("webview_http_error", message)
                                finish(null, IOException(message))
                            }
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (request?.isForMainFrame == true) {
                                val message = "Ошибка загрузки страницы WebView (${error?.errorCode ?: "без кода"}): ${error?.description ?: "неизвестная ошибка сети"}."
                                onEvent("webview_network_error", message)
                                finish(null, IOException(message))
                            }
                        }
                    }
                    onEvent("webview_requested", "Открываю магазин в отдельном WebView-профиле без cookies.")
                    continuation.invokeOnCancellation {
                        webView.post {
                            if (finished.compareAndSet(false, true)) {
                                webView.stopLoading()
                                webView.destroy()
                            }
                        }
                    }
                    webView.loadUrl(response.finalUrl)
                }
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        onEvent("timeout", "Проверка превысила лимит ${TIMEOUT_MS / 1000} секунд; отменён только текущий запрос.")
        throw IOException("Каталог не загрузился за ${TIMEOUT_MS / 1000} секунд. Проверьте сеть и Android System WebView.", timeout)
    }

    private suspend fun probePublicPage(userAgent: String): HttpObservation = withContext(Dispatchers.IO) {
        var address = URL(PublicStoreClient.STORE_URL)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val connection = address.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Cache-Control", "no-cache")
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirect == MAX_REDIRECTS) throw IOException("Магазин перенаправил запрос слишком много раз.")
                    val location = connection.getHeaderField("Location") ?: throw IOException("HTTP $code: сервер не указал адрес перенаправления.")
                    val next = URL(address, location)
                    if (next.protocol != "https" || !next.host.equals("store.avakin.com", true) || (next.port != -1 && next.port != 443)) {
                        throw IOException("Магазин перенаправил запрос за пределы store.avakin.com; проверка остановлена.")
                    }
                    address = next
                    return@repeat
                }
                val mime = connection.contentType?.substringBefore(';') ?: "тип не указан"
                val size = connection.contentLengthLong
                if (code !in 200..299) throw IOException("Официальный магазин вернул HTTP $code ($mime).")
                return@withContext HttpObservation(code, mime, size, address.toString())
            } catch (error: java.net.SocketTimeoutException) {
                throw IOException("Официальный магазин не ответил за ${HTTP_TIMEOUT_MS / 1000} секунд.", error)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Официальный магазин не ответил после перенаправлений.")
    }

    private data class HttpObservation(val statusCode: Int, val contentType: String, val contentLength: Long, val finalUrl: String)

    private companion object {
        const val TIMEOUT_MS = 70_000L
        const val HTTP_TIMEOUT_MS = 18_000
        const val MAX_REDIRECTS = 5
        const val INITIAL_RENDER_WAIT_MS = 4_000L
        const val RENDER_WAIT_MS = 40_000L
        const val POLL_INTERVAL_MS = 1_500L
    }
}
