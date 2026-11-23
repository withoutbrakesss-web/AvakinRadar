package com.avakinradar.app.model

enum class OfferCategory { CROWNS, AVACOINS, CURRENCY_BUNDLE, WEB_EXCLUSIVE, OTHER }

data class StoreOffer(
    val id: String,
    val title: String,
    val price: String? = null,
    val priceCurrency: String? = null,
    val crowns: Int? = null,
    val avacoins: Int? = null,
    val bonus: Int? = null,
    val inAppAmount: Int? = null,
    val description: String? = null,
    val url: String,
    val imageUrl: String? = null,
    val category: OfferCategory,
    val webExclusive: Boolean = false,
    val expiresAt: String? = null
)

enum class ChangeType { NEW, PRICE_CHANGED, CONTENT_CHANGED, REMOVED }

data class OfferChange(
    val type: ChangeType,
    val offer: StoreOffer,
    val previous: StoreOffer? = null,
    val detectedAt: Long = System.currentTimeMillis()
)

data class StoreSnapshot(val offers: List<StoreOffer>, val fetchedAt: Long)
