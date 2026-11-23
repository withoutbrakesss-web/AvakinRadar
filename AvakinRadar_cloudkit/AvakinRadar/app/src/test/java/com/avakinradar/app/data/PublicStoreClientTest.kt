package com.avakinradar.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicStoreClientTest {
    @Test fun refusesNonOfficialOrInsecureSourcesBeforeNetworking() = runBlocking {
        val error = runCatching { PublicStoreClient("http://example.com/").fetchOffers() }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("официальный адрес"))
    }

    @Test fun onlyRealParseableOffersCanProduceASnapshot() {
        val parsed = OfferParser.parse("<html><body><script src='/app.js'></script><div id='root'></div></body></html>")

        assertTrue(parsed.offers.isEmpty())
        assertTrue(parsed.error is String)
    }
}
