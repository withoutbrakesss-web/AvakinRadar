package com.avakinradar.app.data

import com.avakinradar.app.model.OfferCategory
import com.avakinradar.app.model.OfferChange
import com.avakinradar.app.model.StoreOffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferCheckPipelineTest {
    @Test fun fullScenarioFetchesSavesThenReportsNewChangedAndRemovedOffers() = runBlocking {
        val store = MemoryStore()
        val initial = (1..6).map { offer(it) }
        var now = 100L
        val first = OfferCheckPipeline(store, fetchOffers = { _ -> initial }, now = { now++ }).run()

        assertEquals(CheckState.SUCCEEDED, first.summary.state)
        assertEquals(6, first.summary.receivedCount)
        assertEquals(6, first.summary.newCount) // first successful catalog establishes the known baseline
        assertEquals(initial.map { it.id }.toSet(), store.snapshot!!.map { it.id }.toSet())

        val next = listOf(offer(1, price = "8,99 €")) + (2..5).map { offer(it) } + offer(7)
        val second = OfferCheckPipeline(store, fetchOffers = { _ -> next }, now = { now++ }).run()

        assertEquals(CheckState.SUCCEEDED, second.summary.state)
        assertEquals(6, second.summary.receivedCount)
        assertEquals(1, second.summary.newCount)
        assertEquals(1, second.summary.changedCount)
        assertEquals(1, second.summary.removedCount)
        assertEquals(3, second.changes.size)
        assertEquals(4, second.summary.unchangedCount)
        assertEquals(6, store.snapshot!!.size)

        val unchanged = OfferCheckPipeline(store, fetchOffers = { _ -> next }, now = { now++ }).run()
        assertEquals(CheckState.SUCCEEDED, unchanged.summary.state)
        assertEquals(0, unchanged.summary.newCount)
        assertEquals(0, unchanged.summary.changedCount)
        assertEquals(0, unchanged.summary.removedCount)
        assertEquals(6, unchanged.summary.unchangedCount)
        assertTrue(unchanged.changes.isEmpty())

        val savedSnapshot = store.snapshot
        val failed = OfferCheckPipeline(store, fetchOffers = { _ -> (1..3).map { offer(it) } }, now = { now++ }).run()

        assertEquals(CheckState.FAILED, failed.summary.state)
        assertNotNull(failed.summary.error)
        assertTrue(failed.summary.error!!.contains("неполный каталог"))
        assertEquals(savedSnapshot, store.snapshot) // incomplete response never overwrites the last valid snapshot
        assertEquals(CheckState.FAILED, store.lastSummary!!.state)
    }

    private fun offer(id: Int, price: String = "7,99 €") = StoreOffer(
        id = "sku-$id", title = "Публичное предложение $id", price = price,
        priceCurrency = "EUR", crowns = 300, url = "https://store.avakin.com/ru-RU",
        category = OfferCategory.CROWNS
    )

    private class MemoryStore : OfferSnapshotStore {
        var snapshot: List<StoreOffer>? = null
        var lastSummary: CheckSummary? = null
        var history: List<OfferChange> = emptyList()
        override fun loadOffers() = snapshot
        override fun beginCheck(timestamp: Long, workId: String?) { lastSummary = CheckSummary(timestamp, CheckState.RUNNING, workId = workId) }
        override fun startCheckRequest(timestamp: Long, workId: String) { lastSummary = CheckSummary(timestamp, CheckState.RUNNING, workId = workId) }
        override fun appendEvent(event: CheckEvent) { lastSummary = (lastSummary ?: CheckSummary(event.timestamp, CheckState.RUNNING)).let { it.copy(events = it.events + event) } }
        override fun saveSuccess(offers: List<StoreOffer>, changes: List<OfferChange>, summary: CheckSummary) {
            snapshot = offers.toList()
            history = changes + history
            lastSummary = summary.copy(events = (lastSummary?.events.orEmpty() + summary.events + CheckEvent(summary.checkedAt, "result_saved", "Сохранено ${offers.size} предложений.")).distinctBy { "${it.timestamp}:${it.stage}" })
        }
        override fun saveFailure(summary: CheckSummary) { lastSummary = summary }
    }
}
