package com.avakinradar.app.monitor

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.avakinradar.app.data.OfferDiff
import com.avakinradar.app.data.RenderedStoreClient
import com.avakinradar.app.data.SnapshotRepository
import kotlinx.coroutines.CancellationException
import java.io.IOException

class MonitorWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = SnapshotRepository(applicationContext)
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!manual && !repository.settings().monitoringEnabled) return Result.success()

        return try {
            setProgress(workDataOf(MonitorScheduler.PROGRESS_PHASE to "Загружаю каталог магазина…"))
            val offers = RenderedStoreClient(applicationContext).fetchOffers()
            if (offers.isEmpty()) throw IOException("Магазин не вернул проверяемые предложения; предыдущий снимок сохранён.")
            setProgress(workDataOf(MonitorScheduler.PROGRESS_PHASE to "Сравниваю ${offers.size} предложений…"))
            val now = System.currentTimeMillis()
            val previous = repository.loadOffers()
            if (OfferDiff.isSuspiciouslyIncomplete(previous, offers)) {
                throw IOException("Магазин вернул заметно меньше карточек, чем раньше. Снимок сохранён без изменений, чтобы избежать ложных событий.")
            }
            val changes = OfferDiff.compare(previous, offers, now)
            repository.saveSuccess(offers, changes, now)
            if (repository.settings().monitoringEnabled) ChangeNotifications.publish(applicationContext, changes, repository.settings())
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            repository.saveFailure(error.message ?: "Не удалось проверить официальный магазин.")
            if (error is IOException && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KEY_MANUAL = "manual_check"
        private const val MAX_RETRIES = 3
    }
}

