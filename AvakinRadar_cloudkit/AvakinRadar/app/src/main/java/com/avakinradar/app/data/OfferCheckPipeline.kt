package com.avakinradar.app.data

import com.avakinradar.app.model.OfferChange
import com.avakinradar.app.model.StoreOffer
import com.avakinradar.app.model.ChangeType
import kotlinx.coroutines.CancellationException

enum class CheckState { RUNNING, SUCCEEDED, FAILED }

data class CheckEvent(val timestamp: Long, val stage: String, val message: String)

data class CheckSummary(
    val checkedAt: Long,
    val state: CheckState,
    val receivedCount: Int = 0,
    val newCount: Int = 0,
    val changedCount: Int = 0,
    val removedCount: Int = 0,
    val error: String? = null,
    val unchangedCount: Int = 0,
    val events: List<CheckEvent> = emptyList(),
    val workId: String? = null
)

data class OfferCheckOutcome(val summary: CheckSummary, val changes: List<OfferChange> = emptyList())

/** The persistence boundary used by both the Android worker and the end-to-end pipeline tests. */
interface OfferSnapshotStore {
    fun loadOffers(): List<StoreOffer>?
    fun beginCheck(timestamp: Long, workId: String? = null)
    fun startCheckRequest(timestamp: Long, workId: String)
    fun appendEvent(event: CheckEvent)
    fun saveSuccess(offers: List<StoreOffer>, changes: List<OfferChange>, summary: CheckSummary)
    fun saveFailure(summary: CheckSummary)
}

class OfferCheckPipeline(
    private val store: OfferSnapshotStore,
    private val fetchOffers: suspend ((String, String) -> Unit) -> List<StoreOffer>,
    private val workId: String? = null,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun run(onPhase: suspend (String) -> Unit = {}): OfferCheckOutcome {
        store.beginCheck(now(), workId)
        store.appendEvent(CheckEvent(now(), "worker_accepted", "WorkManager запустил обработчик проверки."))
        return try {
            onPhase("Загружаю публичный каталог магазина…")
            store.appendEvent(CheckEvent(now(), "parse_started", "Открываю публичную страницу магазина и ожидаю карточки."))
            val received = fetchOffers { stage, message -> store.appendEvent(CheckEvent(now(), stage, message)) }
            val timestamp = now()
            val current = received.distinctBy(StoreOffer::id)
            if (received.isEmpty()) throw IllegalStateException("Публичная страница не вернула карточки предложений.")
            if (current.size != received.size) throw IllegalStateException("Каталог вернул повторяющиеся карточки; снимок не обновлён.")
            if (current.size < MINIMUM_CATALOG_SIZE) throw IllegalStateException("Получено только ${current.size} карточек. Это неполный каталог; сохранён предыдущий снимок.")
            store.appendEvent(CheckEvent(timestamp, "parse_completed", "Парсер обработал ${current.size} уникальных предложений."))

            val previous = store.loadOffers()
            if (OfferDiff.isSuspiciouslyIncomplete(previous, current)) {
                throw IllegalStateException("Получено заметно меньше карточек, чем в предыдущем каталоге. Снимок сохранён без изменений.")
            }
            onPhase("Сравниваю каталог с предыдущим снимком…")
            store.appendEvent(CheckEvent(timestamp, "comparison_started", "Сравниваю SKU и содержимое карточек с последним успешным снимком."))
            val changes = OfferDiff.compare(previous, current, timestamp)
            val isBaseline = previous.isNullOrEmpty()
            // The first successful scan is a baseline, not an alert-worthy batch of "new" offers.
            val added = if (isBaseline) 0 else changes.count { it.type == ChangeType.NEW }
            val changed = changes.count { it.type == ChangeType.PRICE_CHANGED || it.type == ChangeType.CONTENT_CHANGED }
            val removed = changes.count { it.type == ChangeType.REMOVED }
            val unchanged = if (isBaseline) current.size else (current.size - added - changed).coerceAtLeast(0)
            val summary = CheckSummary(
                checkedAt = timestamp,
                state = CheckState.SUCCEEDED,
                receivedCount = current.size,
                newCount = added,
                changedCount = changed,
                removedCount = removed,
                unchangedCount = unchanged,
                workId = workId
            )
            store.appendEvent(CheckEvent(timestamp, "comparison_completed", "Сравнение завершено: новых $added, изменённых $changed, исчезнувших $removed."))
            onPhase("Сохраняю ${current.size} предложений…")
            store.saveSuccess(current, changes, summary)
            OfferCheckOutcome(summary, changes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val summary = CheckSummary(
                checkedAt = now(),
                state = CheckState.FAILED,
                error = (error.message ?: "Не удалось получить публичный каталог.").take(350),
                workId = workId
            )
            store.appendEvent(CheckEvent(summary.checkedAt, "check_failed", summary.error.orEmpty()))
            store.saveFailure(summary)
            OfferCheckOutcome(summary)
        }
    }

    private companion object { const val MINIMUM_CATALOG_SIZE = 6 }
}
