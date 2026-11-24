package com.avakinradar.app.monitor

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.avakinradar.app.data.OfferCheckPipeline
import com.avakinradar.app.data.CheckEvent
import com.avakinradar.app.data.CheckState
import com.avakinradar.app.data.CheckSummary
import com.avakinradar.app.data.RenderedStoreClient
import com.avakinradar.app.data.SnapshotRepository
import kotlinx.coroutines.CancellationException

class MonitorWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo = MonitorForegroundNotification.create(applicationContext)

    override suspend fun doWork(): Result {
        val repository = SnapshotRepository(applicationContext)
        val settings = repository.settings()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!manual && !settings.monitoringEnabled) return Result.success()

        return try {
            val outcome = OfferCheckPipeline(
                store = repository,
                fetchOffers = { emit -> RenderedStoreClient(applicationContext).fetchOffers(emit) },
                workId = id.toString()
            ).run { phase -> setProgress(workDataOf(MonitorScheduler.PROGRESS_PHASE to phase)) }

            if (outcome.summary.state == CheckState.SUCCEEDED && repository.settings().monitoringEnabled) {
                ChangeNotifications.publish(applicationContext, outcome.changes, repository.settings())
            }
            // Both success and failure have a persisted, visible result. A failed catalog read must
            // not look like an endlessly queued retry or replace the previous valid snapshot.
            Result.success()
        } catch (cancelled: CancellationException) {
            val stoppedAt = System.currentTimeMillis()
            val stopReasonCode = if (Build.VERSION.SDK_INT >= 31) this.stopReason else null
            val stopReasonLabel = stopReasonCode?.let { code ->
                when (code) {
                    WorkInfo.STOP_REASON_USER -> "пользователь или системная отмена"
                    WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "ограничение фоновой работы"
                    WorkInfo.STOP_REASON_QUOTA -> "лимит фоновых задач"
                    WorkInfo.STOP_REASON_DEVICE_STATE -> "состояние устройства или Doze"
                    WorkInfo.STOP_REASON_TIMEOUT -> "таймаут WorkManager"
                    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "ограничение сети"
                    else -> "неизвестная причина Android"
                }
            } ?: "stopReason недоступен на этой версии Android"
            val reason = stopReasonCode?.let { "stopReason=$it ($stopReasonLabel)" } ?: stopReasonLabel
            val message = "Задача проверки остановлена Android до получения результата ($reason, попытка ${runAttemptCount + 1}). Попробуйте запустить её ещё раз."
            repository.saveFailure(CheckSummary(
                checkedAt = stoppedAt,
                state = CheckState.FAILED,
                error = message,
                workId = id.toString()
            ))
            repository.appendEvent(CheckEvent(stoppedAt, "worker_stopped", "WorkManager отменил выполнение: $reason."))
            throw cancelled
        } catch (error: Exception) {
            // This covers persistence/worker-level errors that happened outside the pipeline.
            repository.saveFailure(CheckSummary(
                checkedAt = System.currentTimeMillis(),
                state = CheckState.FAILED,
                error = error.message ?: "Не удалось завершить проверку магазина.",
                workId = id.toString()
            ))
            Result.failure(workDataOf(ERROR_MESSAGE to (error.message ?: "Не удалось завершить фоновую проверку.")))
        }
    }

    companion object {
        const val KEY_MANUAL = "manual_check"
        const val ERROR_MESSAGE = "worker_error_message"
    }
}
