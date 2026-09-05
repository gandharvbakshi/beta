package com.example.beta

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Criteria
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.beta.SwiggyMcpClient.SwiggyAddress
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal data class SwiggyAddressUsage(
    val lastSelectedAtMillis: Long,
    val selectionCount: Int,
)

internal data class SwiggyLocationHint(
    val postalCode: String? = null,
    val subLocality: String? = null,
    val thoroughfare: String? = null,
    val locality: String? = null,
)

internal enum class SwiggyLocationAssessment {
    UNKNOWN,
    AREA_MATCH,
    NOT_MATCHED,
}

internal data class RankedSwiggyAddress(
    val address: SwiggyAddress,
    val reason: String? = null,
    val locationAssessment: SwiggyLocationAssessment = SwiggyLocationAssessment.UNKNOWN,
)

internal fun rankSwiggyAddresses(
    addresses: List<SwiggyAddress>,
    usageByAddressId: Map<String, SwiggyAddressUsage>,
    locationHint: SwiggyLocationHint?,
    recentlyUsedAddressIds: List<String> = emptyList(),
    nowMillis: Long = System.currentTimeMillis(),
): List<RankedSwiggyAddress> {
    val providerRecency = recentlyUsedAddressIds
        .distinct()
        .withIndex()
        .associate { (index, id) -> id to (420 - index.coerceAtMost(7) * 45) }
    val hintHasAreaSignal = hasLocationAreaSignal(locationHint)
    return addresses.mapIndexed { providerIndex, address ->
        val usage = usageByAddressId[address.id]
        val recencyScore = usage?.let {
            val age = (nowMillis - it.lastSelectedAtMillis).coerceAtLeast(0L)
            when {
                age <= 24 * 60 * 60 * 1_000L -> 650
                age <= 7 * 24 * 60 * 60 * 1_000L -> 540
                age <= 30 * 24 * 60 * 60 * 1_000L -> 420
                age <= 90 * 24 * 60 * 60 * 1_000L -> 260
                else -> 100
            } + it.selectionCount.coerceIn(1, 8) * 20
        } ?: 0
        val providerScore = providerRecency[address.id] ?: 0
        val locationScore = locationMatchScore(address, locationHint)
        val reason = when {
            address.hasCurrentCart -> "Current cart address"
            locationScore >= 600 && (recencyScore > 0 || providerScore > 0) -> "Same area · recently used"
            locationScore >= 600 -> "Same area as your location"
            recencyScore > 0 || providerScore > 0 -> "Recently used"
            else -> null
        }
        ScoredAddress(
            ranked = RankedSwiggyAddress(address, reason, when {
                !hintHasAreaSignal -> SwiggyLocationAssessment.UNKNOWN
                locationScore >= 600 -> SwiggyLocationAssessment.AREA_MATCH
                else -> SwiggyLocationAssessment.NOT_MATCHED
            }),
            score = locationScore + recencyScore + providerScore - providerIndex.coerceAtMost(100),
            providerIndex = providerIndex,
        )
    }.sortedWith(
        compareByDescending<ScoredAddress> { it.ranked.address.hasCurrentCart }
            .thenByDescending { it.score }
            .thenBy { it.providerIndex }
    ).map(ScoredAddress::ranked)
}

private data class ScoredAddress(
    val ranked: RankedSwiggyAddress,
    val score: Int,
    val providerIndex: Int,
)

private fun locationMatchScore(address: SwiggyAddress, hint: SwiggyLocationHint?): Int {
    if (hint == null) return 0
    val normalizedAddress = normalizeLocationText(address.label)
    fun contains(value: String?): Boolean {
        val normalized = value?.let(::normalizeLocationText).orEmpty()
        return normalized.length >= 3 && containsTokenPhrase(normalizedAddress, normalized)
    }
    return when {
        contains(hint.postalCode) -> 1_050
        contains(hint.subLocality) -> 850
        contains(hint.thoroughfare) -> 700
        contains(hint.locality) -> 80
        else -> 0
    }
}

private fun hasLocationAreaSignal(hint: SwiggyLocationHint?): Boolean {
    if (hint == null) return false
    return listOf(hint.postalCode, hint.subLocality, hint.thoroughfare)
        .any { !it.isNullOrBlank() && normalizeLocationText(it).length >= 3 }
}

private fun containsTokenPhrase(haystack: String, needle: String): Boolean {
    val hayTokens = haystack.split(Regex("\\s+")).filter { it.isNotBlank() }
    val needleTokens = needle.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (needleTokens.isEmpty() || needleTokens.size > hayTokens.size) return false
    return hayTokens.windowed(needleTokens.size).any { window -> window == needleTokens }
}

private fun normalizeLocationText(value: String): String = value
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()

/**
 * Ranks saved addresses locally. Raw device coordinates and address labels are never logged,
 * persisted, or sent to the Beta backend by this component.
 */
internal class SwiggyAddressIntelligence(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun rank(
        addresses: List<SwiggyAddress>,
        recentlyUsedAddressIds: List<String> = emptyList(),
        callback: (List<RankedSwiggyAddress>) -> Unit,
    ) {
        val usage = addresses.associateNotNull { address ->
            readUsage(address.id)?.let { address.id to it }
        }
        resolveLocationHint { hint ->
            callback(
                rankSwiggyAddresses(
                    addresses = addresses,
                    usageByAddressId = usage,
                    locationHint = hint,
                    recentlyUsedAddressIds = recentlyUsedAddressIds,
                )
            )
        }
    }

    fun recordSelection(addressId: String) {
        if (addressId.isBlank()) return
        val key = usageKey(addressId)
        val current = readUsage(addressId)
        preferences.edit()
            .putLong("${key}_time", System.currentTimeMillis())
            .putInt("${key}_count", (current?.selectionCount ?: 0).plus(1).coerceAtMost(100))
            .apply()
    }

    private fun readUsage(addressId: String): SwiggyAddressUsage? {
        if (addressId.isBlank()) return null
        val key = usageKey(addressId)
        val selectedAt = preferences.getLong("${key}_time", 0L)
        if (selectedAt <= 0L) return null
        return SwiggyAddressUsage(
            lastSelectedAtMillis = selectedAt,
            selectionCount = preferences.getInt("${key}_count", 1).coerceAtLeast(1),
        )
    }

    @SuppressLint("MissingPermission")
    private fun resolveLocationHint(callback: (SwiggyLocationHint?) -> Unit) {
        if (!hasLocationPermission() || !Geocoder.isPresent()) {
            callback(null)
            return
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null || !runCatching { locationManager.isLocationEnabled }.getOrDefault(false)) {
            callback(null)
            return
        }
        val delivered = AtomicBoolean(false)
        fun deliver(hint: SwiggyLocationHint?) {
            if (delivered.compareAndSet(false, true)) callback(hint)
        }
        val provider = runCatching {
            locationManager.getBestProvider(
                Criteria().apply { accuracy = Criteria.ACCURACY_FINE },
                true,
            )
        }.getOrNull()
        val fallback = mostRecentLastKnownLocation(locationManager)
        fun canResolveNow(): Boolean {
            return hasLocationPermission() &&
                runCatching { locationManager.isLocationEnabled }.getOrDefault(false) &&
                Geocoder.isPresent()
        }
        if (provider.isNullOrBlank()) {
            if (canResolveNow()) {
                reverseGeocode(fallback?.takeIf { location -> location.isSwiggyLocationUsable() }, ::deliver)
            } else {
                deliver(null)
            }
            return
        }
        val cancellation = CancellationSignal()
        val timeoutRunnable = Runnable {
            cancellation.cancel()
            if (!delivered.get() && canResolveNow()) {
                reverseGeocode(fallback?.takeIf { location -> location.isSwiggyLocationUsable() }, ::deliver)
            } else if (!delivered.get()) {
                deliver(null)
            }
        }
        mainHandler.postDelayed(timeoutRunnable, LOCATION_TIMEOUT_MILLIS)
        runCatching {
            locationManager.getCurrentLocation(provider, cancellation, context.mainExecutor) { location ->
                val usable = when {
                    location?.isSwiggyLocationUsable() == true -> location
                    fallback?.isSwiggyLocationUsable() == true -> fallback
                    else -> null
                }
                if (!delivered.get()) {
                    if (canResolveNow()) reverseGeocode(usable, ::deliver) else deliver(null)
                }
            }
        }.onFailure {
            Log.i("BetaAgent", "SWIGGY_ADDRESS_LOCATION_UNAVAILABLE")
            if (!delivered.get()) {
                if (canResolveNow()) reverseGeocode(fallback?.takeIf { location -> location.isSwiggyLocationUsable() }, ::deliver)
                else deliver(null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun mostRecentLastKnownLocation(locationManager: LocationManager): Location? {
        if (!hasLocationPermission()) return null
        return runCatching {
            locationManager.getProviders(true)
                .mapNotNull { provider ->
                    if (!hasLocationPermission()) null
                    else runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                }
                .filter { it.isSwiggyLocationUsable() }
                .maxByOrNull { it.elapsedRealtimeNanos }
        }.getOrNull()
    }

    private fun Location.isSwiggyLocationUsable(): Boolean {
        val elapsedNanos = elapsedRealtimeNanos
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        if (elapsedNanos <= 0L || elapsedNanos > nowNanos) return false
        val ageMillis = (nowNanos - elapsedNanos) / 1_000_000L
        return isSwiggyLocationUsable(ageMillis, accuracy)
    }

    private fun reverseGeocode(location: Location?, callback: (SwiggyLocationHint?) -> Unit) {
        if (location == null) {
            callback(null)
            return
        }
        val delivered = AtomicBoolean(false)
        fun deliver(hint: SwiggyLocationHint?) {
            if (delivered.compareAndSet(false, true)) callback(hint)
        }
        mainHandler.postDelayed({ deliver(null) }, GEOCODER_TIMEOUT_MILLIS)
        runCatching {
            Geocoder(context, Locale("en", "IN")).getFromLocation(
                location.latitude,
                location.longitude,
                1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        deliver(addresses.firstOrNull()?.toLocationHint())
                    }

                    override fun onError(errorMessage: String?) {
                        deliver(null)
                    }
                }
            )
        }.onFailure { deliver(null) }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun Address.toLocationHint(): SwiggyLocationHint = SwiggyLocationHint(
        postalCode = postalCode,
        subLocality = subLocality,
        thoroughfare = thoroughfare,
        locality = locality,
    )

    private fun usageKey(addressId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(addressId.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { byte -> "%02x".format(byte) }
    }

    private inline fun <K, V : Any> Iterable<K>.associateNotNull(transform: (K) -> Pair<String, V>?): Map<String, V> {
        return buildMap {
            for (element in this@associateNotNull) transform(element)?.let { (key, value) -> put(key, value) }
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "swiggy_address_intelligence"
        private const val LOCATION_TIMEOUT_MILLIS = 2_500L
        private const val GEOCODER_TIMEOUT_MILLIS = 2_500L
    }
}

internal fun isSwiggyLocationUsable(ageMillis: Long, accuracyMeters: Float): Boolean {
    return ageMillis in 0..300_000L &&
        accuracyMeters.isFinite() &&
        accuracyMeters > 0f &&
        accuracyMeters <= 2_000f
}
