package com.aesprt.aquahub_customer.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.aesprt.aquahub_customer.BuildConfig
import com.aesprt.aquahub_customer.domain.AppResult
import com.aesprt.aquahub_customer.domain.GeoPoint
import com.aesprt.aquahub_customer.domain.LocationRepository
import com.aesprt.aquahub_customer.domain.LocationSuggestion
import com.aesprt.aquahub_customer.domain.ResolvedLocation
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

class AndroidLocationRepository(
    context: Context,
) : LocationRepository {
    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)
    private val placesClient: PlacesClient? = runCatching {
        if (BuildConfig.MAPS_API_KEY.isBlank()) return@runCatching null
        if (!Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(appContext, BuildConfig.MAPS_API_KEY)
        }
        Places.createClient(appContext)
    }.getOrNull()

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): AppResult<GeoPoint> = runCatching {
        val tokenSource = CancellationTokenSource()
        val current = client.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            tokenSource.token,
        ).await()
        val location = current ?: client.lastLocation.await()
            ?: error("Your location is not available. Turn on Location and try again.")
        GeoPoint(location.latitude, location.longitude)
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Failure(it.message ?: "Could not get your current location.") },
    )

    override suspend fun searchPlaces(query: String): AppResult<List<LocationSuggestion>> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.length < 2) return@withContext AppResult.Success(emptyList())

            val placesResult = placesClient?.let { places ->
                runCatching {
                    val request = FindAutocompletePredictionsRequest.builder()
                        .setQuery(trimmed)
                        .build()
                    places.findAutocompletePredictions(request).await().autocompletePredictions.map { prediction ->
                        LocationSuggestion(
                            placeId = prediction.placeId,
                            primaryText = prediction.getPrimaryText(null).toString(),
                            secondaryText = prediction.getSecondaryText(null).toString().ifBlank { null },
                        )
                    }
                }.getOrNull()
            }
            if (placesResult != null) return@withContext AppResult.Success(placesResult)

            runCatching { geocodeByName(trimmed) }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = { AppResult.Failure(it.message ?: "Location search is unavailable.") },
            )
        }

    override suspend fun resolvePlace(placeId: String): AppResult<ResolvedLocation> =
        withContext(Dispatchers.IO) {
            decodeGeocoderPlaceId(placeId)?.let { return@withContext reverseGeocode(it) }
            val places = placesClient
                ?: return@withContext AppResult.Failure("Google Places is not configured for this build.")
            runCatching {
                val request = FetchPlaceRequest.newInstance(
                    placeId,
                    listOf(Place.Field.ID, Place.Field.DISPLAY_NAME, Place.Field.FORMATTED_ADDRESS, Place.Field.LOCATION),
                )
                val place = places.fetchPlace(request).await().place
                val point = place.location?.let { GeoPoint(it.latitude, it.longitude) }
                    ?: error("The selected place has no coordinates.")
                ResolvedLocation(
                    placeId = place.id,
                    formattedAddress = place.formattedAddress ?: place.displayName ?: "Selected location",
                    point = point,
                )
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = { AppResult.Failure(it.message ?: "Could not load the selected location.") },
            )
        }

    override suspend fun reverseGeocode(point: GeoPoint): AppResult<ResolvedLocation> =
        withContext(Dispatchers.IO) {
            runCatching {
                val address = geocodePoint(point).firstOrNull()
                val formatted = address?.let { value ->
                    (0..value.maxAddressLineIndex)
                        .mapNotNull(value::getAddressLine)
                        .joinToString(", ")
                }.orEmpty().ifBlank {
                    "Location (${String.format(Locale.US, "%.5f", point.latitude)}, ${String.format(Locale.US, "%.5f", point.longitude)})"
                }
                ResolvedLocation(formattedAddress = formatted, point = point)
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = {
                    AppResult.Success(
                        ResolvedLocation(
                            formattedAddress = "Location (${String.format(Locale.US, "%.5f", point.latitude)}, ${String.format(Locale.US, "%.5f", point.longitude)})",
                            point = point,
                        ),
                    )
                },
            )
        }

    private suspend fun geocodeByName(query: String): List<LocationSuggestion> =
        geocodeName(query).mapIndexed { index, address ->
            LocationSuggestion(
                placeId = "geo_${address.latitude}_${address.longitude}_$index",
                primaryText = address.featureName ?: address.thoroughfare ?: query,
                secondaryText = listOfNotNull(address.subLocality, address.locality, address.adminArea, address.countryName)
                    .filter(String::isNotBlank)
                    .joinToString(", ")
                    .ifBlank { null },
            )
        }

    private suspend fun geocodeName(query: String): List<android.location.Address> {
        val geocoder = Geocoder(appContext, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocationName(query, 5) { continuation.resume(it) }
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocationName(query, 5).orEmpty()
        }
    }

    private suspend fun geocodePoint(point: GeoPoint): List<android.location.Address> {
        val geocoder = Geocoder(appContext, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(point.latitude, point.longitude, 1) { continuation.resume(it) }
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(point.latitude, point.longitude, 1).orEmpty()
        }
    }

    private fun decodeGeocoderPlaceId(placeId: String): GeoPoint? {
        if (!placeId.startsWith("geo_")) return null
        val parts = placeId.removePrefix("geo_").split("_")
        val latitude = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val longitude = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return runCatching { GeoPoint(latitude, longitude) }.getOrNull()
    }
}
