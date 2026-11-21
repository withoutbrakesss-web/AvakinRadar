package com.avakinradar.app.data

import com.avakinradar.app.model.OfferChange
import com.avakinradar.app.model.StoreOffer
import kotlinx.coroutines.CancellationException

enum class CheckState { RUNNING, SUCCEEDED, FAILED }

data class CheckSummary(
    val checkedAt: Long,
    val state: CheckState,
    val receivedCount: Int = 0,
    val newCount: Int = 0,
    val changedCount: Int = 0,
    val removedCount: Int = 0,
    val error: String? = null
)

data class OfferCheckOutcome(val summary: CheckSummary, val changes: List<OfferChange> = emptyList())

/** The persistence boundary used by both the Android worker and the end-to-end pipeline tests. */
interface OfferSnapshotStore {
    fun loadOffers(): List<StoreOffer>?
    fun beginCheck(timestamp: Long)
    fun saveSuccess(offers: List<StoreOffer>, changes: List<OfferChange>, summary: CheckSummary)
    fun saveFailure(summary: CheckSummary)
}

class OfferCheckPipeline(
    private val store: OfferSnapshotStore,
    private val fetchOffers: suspend () -> List<StoreOffer>,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun run(onPhase: suspend (String) -> Unit = {}): OfferCheckOutcome {
        store.beginCheck(now())
        return try {
            onPhase("Загружаю публичный каталог магазина…")
            val received = fetchOffers()
            val timestamp = now()
            val current = received.distinctBy(StoreOffer::id)
            if (received.isEmpty()) throw IllegalStateException("Публичная страница не вернула карточки предложений.")
            if (current.size != received.size) throw IllegalStateException("Каталог вернул повторяющиеся карточки; снимок не обновлён.")
            if (current.size < MINIMUM_CATALOG_SIZE) throw IllegalStateException("Получено только ${current.size} карточек. Это неполный каталог; сохранён предыдущий снимок.")

            val previous = store.loadOffers()
            if (OfferDiff.isSuspiciouslyIncomplete(previous, current)) {
                throw IllegalStateException("Получено заметно меньше карточек, чем в предыдущем каталоге. Снимок сохранён без изменений.")
            }
            onPhase("Сравниваю каталог с предыдущим снимком…")
            val changes = OfferDiff.compare(previous, current, timestamp)
            val summary = CheckSummary(
                checkedAt = timestamp,
                state = CheckState.SUCCEEDED,
                receivedCount = current.size,
                newCount = if (previous == null) current.size else changes.count { it.type == com.avakinradar.app.model.ChangeType.NEW },
                changedCount = changes.count { it.type == com.avakinradar.app.model.ChangeType.PRICE_CHANGED || it.type == com.avakinradar.app.model.ChangeType.CONTENT_CHANGED },
                removedCount = changes.count { it.type == com.avakinradar.app.model.ChangeType.REMOVED }
            )
            store.saveSuccess(current, changes, summary)
            OfferCheckOutcome(summary, changes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val summary = CheckSummary(
                checkedAt = now(),
                state = CheckState.FAILED,
                error = (error.message ?: "Не удалось получить публичный каталог.").take(350)
            )
            store.saveFailure(summary)
            OfferCheckOutcome(summary)
        }
    }

    private companion object { const val MINIMUM_CATALOG_SIZE = 6 }
}

