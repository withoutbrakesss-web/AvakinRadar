package com.avakinradar.app.data

import com.avakinradar.app.model.ChangeType
import com.avakinradar.app.model.OfferCategory
import com.avakinradar.app.model.OfferChange
import com.avakinradar.app.model.StoreOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferDiffTest {
    private fun offer(
        id: String = "offer/coins",
        title: String = "Avacoins Pack",
        price: String = "$19.99",
        avacoins: Int = 33_000
    ) = StoreOffer(
        id = id, title = title, price = price, priceCurrency = "USD", avacoins = avacoins,
        url = "https://store.avakin.com/$id", category = OfferCategory.AVACOINS
    )

    @Test fun initialSnapshotIsABaselineWithNoChanges() {
        assertTrue(OfferDiff.compare(null, listOf(offer()), now = 1L).isEmpty())
    }

    @Test fun detectsNewPriceChangedContentChangedAndRemovedOffers() {
        val old = listOf(
            offer(),
            offer(id = "offer/crowns", title = "Crowns Pack", price = "$9.99", avacoins = 0).copy(crowns = 280, avacoins = null),
            offer(id = "offer/removed", title = "Expired Pack")
        )
        val current = listOf(
            offer(price = "$17.99"),
            old[1].copy(crowns = 300),
            offer(id = "offer/new", title = "New Bundle", price = "$5.99", avacoins = 8_000)
        )

        val changes = OfferDiff.compare(old, current, now = 10L)

        assertEquals(listOf(ChangeType.PRICE_CHANGED, ChangeType.CONTENT_CHANGED, ChangeType.NEW, ChangeType.REMOVED), changes.map(OfferChange::type))
        assertEquals(10L, changes.first().detectedAt)
        assertEquals("$19.99", changes.last().offer.price)
    }

    @Test fun priceCurrencyChangesAreNotLost() {
        val old = offer().copy(priceCurrency = "USD")
        val current = old.copy(price = "€18.00", priceCurrency = "EUR")

        assertEquals(ChangeType.PRICE_CHANGED, OfferDiff.compare(listOf(old), listOf(current)).single().type)
    }

    @Test fun identicalSnapshotIsDeduplicatedByOfferId() {
        val item = offer()
        assertTrue(OfferDiff.compare(listOf(item), listOf(item, item)).isEmpty())
    }

    @Test fun refusesToReplaceASnapshotWhenMostCardsSuddenlyDisappear() {
        val old = (1..10).map { offer(id = "offer/$it", title = "Offer $it") }

        assertTrue(OfferDiff.isSuspiciouslyIncomplete(old, old.take(4)))
        assertTrue(!OfferDiff.isSuspiciouslyIncomplete(old, old.take(5)))
        assertTrue(!OfferDiff.isSuspiciouslyIncomplete(null, old.take(1)))
    }
}
