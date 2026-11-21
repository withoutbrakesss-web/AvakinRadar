package com.avakinradar.app.data

import com.avakinradar.app.model.ChangeType
import com.avakinradar.app.model.OfferCategory
import com.avakinradar.app.model.OfferChange
import com.avakinradar.app.model.StoreOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfferJsonTest {
    private val offer = StoreOffer(
        id = "offer/bundle", title = "Currency Bundle", price = "$9.99", priceCurrency = "USD",
        crowns = 280, avacoins = 14_000, bonus = 25, inAppAmount = 255,
        description = "Public shop offer", url = "https://store.avakin.com/offer/bundle",
        imageUrl = null, category = OfferCategory.CURRENCY_BUNDLE, webExclusive = true,
        expiresAt = "2026-12-01T00:00:00Z"
    )

    @Test fun snapshotEntryRoundTripsOptionalFieldsAndCurrencyAmounts() {
        assertEquals(offer, OfferJson.decode(OfferJson.encode(offer)))
    }

    @Test fun historyEntryRoundTripsAndSupportsMissingPreviousOffer() {
        val item = OfferChange(ChangeType.NEW, offer, previous = null, detectedAt = 123L)

        val decoded = OfferJson.decodeChange(OfferJson.encodeChange(item))

        assertEquals(item, decoded)
        assertNull(decoded?.previous)
    }
}
