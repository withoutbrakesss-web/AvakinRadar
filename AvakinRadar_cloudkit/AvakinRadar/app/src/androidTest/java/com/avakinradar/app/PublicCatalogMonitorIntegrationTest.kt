package com.avakinradar.app

import android.content.Context
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avakinradar.app.data.CheckState
import com.avakinradar.app.data.CheckSummary
import com.avakinradar.app.data.SnapshotRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real public storefront -> Android WebView -> WorkManager -> saved snapshot -> Compose UI. */
@RunWith(AndroidJUnit4::class)
class PublicCatalogMonitorIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repository: SnapshotRepository get() = SnapshotRepository(context)

    @Before fun clearOnlyTheEmulatorTestAppState() {
        context.getSharedPreferences("avakin_radar_local", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun buttonFetchesRealPublicOffersPersistsThemAndShowsResultThenComparesAgain() {
        compose.onNodeWithText("Проверить предложения").assertExists().performClick()
        val first = awaitTerminalCheck()

        assertEquals("First device check failed: ${first?.error}\n${first?.events?.joinToString("\n") { "${it.stage}: ${it.message}" }}", CheckState.SUCCEEDED, first?.state)
        assertTrue("The live public catalog returned fewer than six valid cards.", first!!.receivedCount >= 6)
        assertEquals(first.receivedCount, repository.loadOffers()?.size)
        assertTrue(first.events.any { it.stage == "http_response" && it.message.contains("200") })
        assertTrue(first.events.any { it.stage == "cards_parsed" })
        val firstCard = repository.loadOffers()!!.first()
        compose.waitForIdle()
        compose.onNodeWithText("Получено предложений: ${first.receivedCount}").assertExists()
        compose.onNodeWithText(firstCard.title).assertExists()

        compose.onNodeWithText("Проверить предложения").assertExists().performClick()
        val second = awaitTerminalCheck(after = first.checkedAt)
        assertEquals("Second device check failed: ${second?.error}", CheckState.SUCCEEDED, second?.state)
        assertEquals(second!!.receivedCount, repository.loadOffers()?.size)
        assertEquals(second.receivedCount, second.newCount + second.changedCount + second.unchangedCount)
        assertTrue(second.events.any { it.stage == "comparison_completed" })
        compose.waitForIdle()
        compose.onNodeWithText("Получено предложений: ${second.receivedCount}").assertExists()
    }

    private fun awaitTerminalCheck(after: Long = 0L): CheckSummary {
        compose.waitUntil(timeoutMillis = 130_000) {
            val result = repository.status().lastCheck
            result != null && result.checkedAt > after && result.state != CheckState.RUNNING
        }
        val result = repository.status().lastCheck
        return result ?: throw AssertionError("The button did not produce a stored check result.")
    }
}
