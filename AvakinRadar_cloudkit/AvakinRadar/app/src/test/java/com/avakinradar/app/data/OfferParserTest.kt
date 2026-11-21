package com.avakinradar.app.data

import com.avakinradar.app.model.OfferCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferParserTest {
    @Test fun parsesLiveXsollaArticleCardsWithSkuAndRussianLabels() {
        val cards = (1..6).joinToString("") { index ->
            val name = if (index == 1) "Стиль Пугала" else "Публичное предложение $index"
            """<article class="avsp-card has-pop" data-sku="sku-$index" aria-label="$name" role="button">
                <div class="avsp-artbox">1&nbsp;295 Crowns ♀♂</div>
                <div class="avsp-name"><bdi>$name</bdi></div>
                <div class="avsp-vrow is-c-crown">+120 бонус В приложении: ⁨1&nbsp;175⁩</div>
                <div class="avsp-act">37,99&nbsp;€</div>
                <div class="avsp-hint">Войди, чтобы купить</div>
                <div class="avsp-art"><img src="https://cdn.xsolla.net/catalog/$index.png"></div>
            </article>"""
        }
        val parsed = OfferParser.parse("<html><body>$cards</body></html>", "https://store.avakin.com/ru-RU")

        assertEquals(6, parsed.offers.size)
        val first = parsed.offers.first { it.id == "sku-1" }
        assertEquals("Стиль Пугала", first.title)
        assertEquals(1295, first.crowns)
        assertEquals(120, first.bonus)
        assertEquals(1175, first.inAppAmount)
        assertEquals("37,99 €", first.price)
        assertEquals("EUR", first.priceCurrency)
        assertEquals("https://store.avakin.com/ru-RU", first.url)
        assertEquals("https://cdn.xsolla.net/catalog/1.png", first.imageUrl)
    }

    @Test fun parsesPublicCurrencyCardAndNormalizesAmounts() {
        val result = OfferParser.parse(
            """
            <html><body><article>
              <a href="/en-US/offer/maple-hideaway">
                <img src="https://images.example.test/maple.png" />
                <h3>Maple Hideaway</h3>
                <p>1,045 Crowns</p><p>+95 bonus</p><p>In the app: 950</p><strong>$29.99</strong>
              </a>
            </article></body></html>
            """.trimIndent()
        )

        assertNull(result.error)
        assertEquals(1, result.offers.size)
        val offer = result.offers.single()
        assertEquals("Maple Hideaway", offer.title)
        assertEquals(1_045, offer.crowns)
        assertEquals(95, offer.bonus)
        assertEquals(950, offer.inAppAmount)
        assertEquals("$29.99", offer.price)
        assertEquals("USD", offer.priceCurrency)
        assertEquals(OfferCategory.CROWNS, offer.category)
        assertTrue(offer.url.startsWith("https://store.avakin.com/"))
        assertNotNull(offer.imageUrl)
    }

    @Test fun deduplicatesProductsAndRejectsOffsiteProductLinks() {
        val card = """
            <article><a href="/offer/coins"><h3>Avacoins Pack</h3><p>33,000 Avacoins</p><strong>$19.99</strong></a></article>
        """.trimIndent()
        val result = OfferParser.parse("<body>$card$card<article><a href='https://example.com'><h3>Other</h3><b>$7.99</b></a></article></body>")

        assertEquals(1, result.offers.size)
        assertEquals(33_000, result.offers.single().avacoins)
        assertEquals(OfferCategory.AVACOINS, result.offers.single().category)
    }

    @Test fun emptyDynamicShellFailsClosedInsteadOfReportingRemovals() {
        val result = OfferParser.parse("<html><body><div id='app'></div><script src='/app.js'></script></body></html>")

        assertTrue(result.offers.isEmpty())
        assertNotNull(result.error)
        assertFalse(result.error.orEmpty().contains("new", ignoreCase = true))
    }

    @Test fun accessChallengeIsReportedWithoutAttemptingToBypassIt() {
        val result = OfferParser.parse("<html><body><div class='captcha'>Verify you are human</div></body></html>")

        assertTrue(result.offers.isEmpty())
        assertTrue(result.error.orEmpty().contains("не обходит", ignoreCase = true))
    }

    @Test fun parsesStructuredProductData() {
        val html = """
            <script type="application/ld+json">
              {"@type":"Product","name":"Crown Pack","description":"330 Crowns +30 bonus","url":"https://store.avakin.com/offer/crown-pack","image":"https://images.example.test/crown.png","offers":{"price":"7.99","priceCurrency":"USD"}}
            </script>
        """.trimIndent()
        val offer = OfferParser.parse(html).offers.single()

        assertEquals("Crown Pack", offer.title)
        assertEquals(330, offer.crowns)
        assertEquals(30, offer.bonus)
        assertEquals("USD", offer.priceCurrency)
    }

    @Test fun recognizesRegionalPricesWithTrailingCurrencySymbol() {
        val html = """
            <article><a href="/ru-RU/offer/crowns"><h3>280 Crowns</h3><strong>14,99 US$</strong></a></article>
        """.trimIndent()

        val offer = OfferParser.parse(html).offers.single()
        assertEquals("USD", offer.priceCurrency)
        assertEquals("14,99 US$", offer.price)
    }
}
