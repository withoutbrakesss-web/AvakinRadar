package com.avakinradar.app.data

import com.avakinradar.app.model.ChangeType
import com.avakinradar.app.model.OfferChange
import com.avakinradar.app.model.StoreOffer

object OfferDiff {
    /** A null previous snapshot is the initial baseline and deliberately emits no alerts. */
    fun compare(previous: List<StoreOffer>?, current: List<StoreOffer>, now: Long = System.currentTimeMillis()): List<OfferChange> {
        if (previous == null) return emptyList()

        val oldById = previous.associateBy(StoreOffer::id)
        val newById = current.associateBy(StoreOffer::id)
        val changes = mutableListOf<OfferChange>()

        current.forEach { offer ->
            val old = oldById[offer.id]
            when {
                old == null -> changes += OfferChange(ChangeType.NEW, offer, detectedAt = now)
                old.price != offer.price || old.priceCurrency != offer.priceCurrency ->
                    changes += OfferChange(ChangeType.PRICE_CHANGED, offer, old, now)
                old.title != offer.title || old.crowns != offer.crowns || old.avacoins != offer.avacoins ||
                    old.bonus != offer.bonus || old.inAppAmount != offer.inAppAmount ||
                    old.description != offer.description || old.category != offer.category || old.url != offer.url ||
                    old.webExclusive != offer.webExclusive || old.expiresAt != offer.expiresAt ->
                    changes += OfferChange(ChangeType.CONTENT_CHANGED, offer, old, now)
            }
        }

        previous.filter { it.id !in newById }.forEach { old ->
            changes += OfferChange(ChangeType.REMOVED, old, old, now)
        }
        return changes
    }

    /** A dramatic count drop often means the public page returned only part of its catalog. */
    fun isSuspiciouslyIncomplete(previous: List<StoreOffer>?, current: List<StoreOffer>): Boolean =
        previous != null && previous.size >= 6 && current.size * 2 < previous.size
}
