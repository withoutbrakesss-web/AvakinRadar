package com.avakinradar.app.data

import android.content.Context
import android.content.SharedPreferences
import com.avakinradar.app.model.ChangeType
import com.avakinradar.app.model.OfferChange
import com.avakinradar.app.model.OfferCategory
import com.avakinradar.app.model.StoreOffer
import org.json.JSONArray
import org.json.JSONObject

data class MonitorSettings(
    val monitoringEnabled: Boolean = false,
    val notifyNew: Boolean = true,
    val notifyChanges: Boolean = true,
    val notifyRemoved: Boolean = true,
    val intervalHours: Long = 6
)

data class MonitorStatus(
    val lastCheckedAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
    val offerCount: Int = 0
)

class SnapshotRepository(context: Context) {
    val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(): MonitorSettings = MonitorSettings(
        monitoringEnabled = preferences.getBoolean(KEY_ENABLED, false),
        notifyNew = preferences.getBoolean(KEY_NOTIFY_NEW, true),
        notifyChanges = preferences.getBoolean(KEY_NOTIFY_CHANGES, true),
        notifyRemoved = preferences.getBoolean(KEY_NOTIFY_REMOVED, true),
        intervalHours = preferences.getLong(KEY_INTERVAL, 6L).coerceIn(6L, 24L)
    )

    fun updateSettings(settings: MonitorSettings) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, settings.monitoringEnabled)
            .putBoolean(KEY_NOTIFY_NEW, settings.notifyNew)
            .putBoolean(KEY_NOTIFY_CHANGES, settings.notifyChanges)
            .putBoolean(KEY_NOTIFY_REMOVED, settings.notifyRemoved)
            .putLong(KEY_INTERVAL, settings.intervalHours.coerceIn(6L, 24L))
            .commit()
    }

    fun loadOffers(): List<StoreOffer>? {
        val raw = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { OfferJson.decode(array.optJSONObject(it)) }
        }.getOrNull()
    }

    fun status(): MonitorStatus = MonitorStatus(
        lastCheckedAt = preferences.getLong(KEY_LAST_CHECKED, 0L).takeIf { it > 0 },
        lastSuccessAt = preferences.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0 },
        lastError = preferences.getString(KEY_LAST_ERROR, null),
        offerCount = loadOffers()?.size ?: 0
    )

    fun history(limit: Int = 250): List<OfferChange> {
        val raw = preferences.getString(KEY_HISTORY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { OfferJson.decodeChange(array.optJSONObject(it)) }
                .sortedByDescending { it.detectedAt }
                .take(limit)
        }.getOrDefault(emptyList())
    }

    fun saveSuccess(offers: List<StoreOffer>, changes: List<OfferChange>, timestamp: Long) {
        val nextHistory = (changes.map { OfferJson.encodeChange(it) } + history(250).map { OfferJson.encodeChange(it) }).take(250)
        val snapshot = JSONArray().apply { offers.forEach { put(OfferJson.encode(it)) } }
        val historyJson = JSONArray().apply { nextHistory.forEach { put(it) } }
        preferences.edit()
            .putString(KEY_SNAPSHOT, snapshot.toString())
            .putString(KEY_HISTORY, historyJson.toString())
            .putLong(KEY_LAST_CHECKED, timestamp)
            .putLong(KEY_LAST_SUCCESS, timestamp)
            .remove(KEY_LAST_ERROR)
            .commit()
    }

    fun saveFailure(message: String, timestamp: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putLong(KEY_LAST_CHECKED, timestamp)
            .putString(KEY_LAST_ERROR, message.take(350))
            .commit()
    }

    private companion object {
        const val PREFS = "avakin_radar_local"
        const val KEY_ENABLED = "monitor_enabled"
        const val KEY_NOTIFY_NEW = "notify_new"
        const val KEY_NOTIFY_CHANGES = "notify_changes"
        const val KEY_NOTIFY_REMOVED = "notify_removed"
        const val KEY_INTERVAL = "interval_hours"
        const val KEY_SNAPSHOT = "offer_snapshot"
        const val KEY_HISTORY = "offer_history"
        const val KEY_LAST_CHECKED = "last_checked"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_ERROR = "last_error"
    }
}

internal object OfferJson {
    fun encode(offer: StoreOffer): JSONObject = JSONObject().apply {
        put("id", offer.id)
        put("title", offer.title)
        putNullable("price", offer.price)
        putNullable("priceCurrency", offer.priceCurrency)
        putNullable("crowns", offer.crowns)
        putNullable("avacoins", offer.avacoins)
        putNullable("bonus", offer.bonus)
        putNullable("inAppAmount", offer.inAppAmount)
        putNullable("description", offer.description)
        put("url", offer.url)
        putNullable("imageUrl", offer.imageUrl)
        put("category", offer.category.name)
        put("webExclusive", offer.webExclusive)
        putNullable("expiresAt", offer.expiresAt)
    }

    fun decode(json: JSONObject?): StoreOffer? = runCatching {
        json ?: return null
        StoreOffer(
            id = json.getString("id"), title = json.getString("title"),
            price = json.nullableString("price"), priceCurrency = json.nullableString("priceCurrency"),
            crowns = json.nullableInt("crowns"), avacoins = json.nullableInt("avacoins"),
            bonus = json.nullableInt("bonus"), inAppAmount = json.nullableInt("inAppAmount"),
            description = json.nullableString("description"), url = json.getString("url"),
            imageUrl = json.nullableString("imageUrl"),
            category = OfferCategory.valueOf(json.optString("category", OfferCategory.OTHER.name)),
            webExclusive = json.optBoolean("webExclusive", false), expiresAt = json.nullableString("expiresAt")
        )
    }.getOrNull()

    fun encodeChange(change: OfferChange): JSONObject = JSONObject().apply {
        put("type", change.type.name)
        put("offer", encode(change.offer))
        put("previous", change.previous?.let(::encode) ?: JSONObject.NULL)
        put("detectedAt", change.detectedAt)
    }

    fun decodeChange(json: JSONObject?): OfferChange? = runCatching {
        json ?: return null
        val offer = decode(json.optJSONObject("offer")) ?: return null
        val previous = decode(json.optJSONObject("previous"))
        OfferChange(
            type = ChangeType.valueOf(json.getString("type")),
            offer = offer,
            previous = previous,
            detectedAt = json.optLong("detectedAt", System.currentTimeMillis())
        )
    }.getOrNull()

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.nullableString(key: String): String? =
        opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()

    private fun JSONObject.nullableInt(key: String): Int? =
        opt(key)?.takeUnless { it == JSONObject.NULL }?.let { (it as? Number)?.toInt() ?: it.toString().toIntOrNull() }
}
