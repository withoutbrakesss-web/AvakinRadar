package com.avakinradar.app.data

import android.content.Context
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reads the same logged-out, public catalog that the storefront renders in its browser. */
class RenderedStoreClient(context: Context) {
    private val appContext = context.applicationContext

    suspend fun fetchOffers(): List<com.avakinradar.app.model.StoreOffer> = withTimeout(TIMEOUT_MS) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(appContext)
                val finished = AtomicBoolean(false)
                val startedAt = SystemClock.elapsedRealtime()

                fun finish(offers: List<com.avakinradar.app.model.StoreOffer>?, error: Throwable?) {
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
                            finish(parsed.offers, null)
                        } else if (SystemClock.elapsedRealtime() - startedAt >= RENDER_WAIT_MS) {
                            finish(null, IOException(parsed.error ?: "В публичной странице не найдены карточки магазина."))
                        } else {
                            webView.postDelayed(::inspectRenderedPage, POLL_INTERVAL_MS)
                        }
                    }
                }

                CookieManager.getInstance().setAcceptCookie(false)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.useWideViewPort = false
                webView.settings.loadWithOverviewMode = false
                webView.settings.allowFileAccess = false
                webView.settings.allowContentAccess = false
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (url?.startsWith("https://store.avakin.com/", ignoreCase = true) == true) {
                            view?.postDelayed(::inspectRenderedPage, INITIAL_RENDER_WAIT_MS)
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) {
                            finish(null, IOException(error?.description?.toString() ?: "Не удалось загрузить публичный магазин."))
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    webView.post {
                        if (finished.compareAndSet(false, true)) {
                            webView.stopLoading()
                            webView.destroy()
                        }
                    }
                }
                webView.loadUrl(PublicStoreClient.STORE_URL)
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 55_000L
        const val INITIAL_RENDER_WAIT_MS = 4_000L
        const val RENDER_WAIT_MS = 35_000L
        const val POLL_INTERVAL_MS = 1_500L
    }
}

