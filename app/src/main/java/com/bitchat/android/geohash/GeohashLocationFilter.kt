package com.bitchat.android.geohash

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sophisticated geohash location filter to prevent notifications from distant locations
 * Addresses the issue where users receive #9q notifications from different continents
 */
class GeohashLocationFilter private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "GeohashLocationFilter"
        
        // Maximum distance in kilometers for relevant geohash notifications
        private const val MAX_NOTIFICATION_DISTANCE_KM = 500.0 // 500km radius
        
        // Special geohashes that should always be filtered out if too far
        private val PROBLEMATIC_GEOHASHES = setOf(
            "9q", "9r", "9x", "9z", // California/West Coast US  
            "dr", "dj", "9v", "9y", // East Coast US
            "u1", "u0", "u4", "u6", // Europe 
            "w2", "w0", "w1", "w3", // Asia Pacific
        )
        
        @Volatile
        private var INSTANCE: GeohashLocationFilter? = null
        
        fun getInstance(context: Context): GeohashLocationFilter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GeohashLocationFilter(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var lastKnownLocation: Location? = null
    
    init {
        updateLastKnownLocation()
    }
    
    /**
     * Update the user's last known location from available providers
     */
    @Suppress("MissingPermission")
    fun updateLastKnownLocation() {
        try {
            // Try GPS first, then network
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            
            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    val location = locationManager.getLastKnownLocation(provider)
                    if (location != null && (lastKnownLocation == null || 
                            location.time > lastKnownLocation!!.time)) {
                        lastKnownLocation = location
                        Log.d(TAG, "Updated location from $provider: ${location.latitude}, ${location.longitude}")
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted, using default filtering")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating location: ${e.message}")
        }
    }
    
    /**
     * Check if a geohash notification should be shown based on user's location
     */
    fun shouldShowGeohashNotification(geohash: String, senderLocation: String? = null): Boolean {
        // Always filter problematic geohashes if we have user location
        if (lastKnownLocation != null && PROBLEMATIC_GEOHASHES.any { geohash.startsWith(it) }) {
            val geohashLocation = decodeGeohashToLocation(geohash)
            if (geohashLocation != null) {
                val distance = calculateDistance(lastKnownLocation!!, geohashLocation)
                
                if (distance > MAX_NOTIFICATION_DISTANCE_KM) {
                    Log.d(TAG, "Filtering geohash $geohash notification - distance: ${distance.toInt()}km > ${MAX_NOTIFICATION_DISTANCE_KM}km")
                    return false
                }
            }
        }
        
        // If no location available, be more conservative with short geohashes
        if (lastKnownLocation == null) {
            // Filter very short geohashes (continent-level) that are likely too broad
            if (geohash.length <= 2 && PROBLEMATIC_GEOHASHES.contains(geohash)) {
                Log.d(TAG, "Filtering broad geohash $geohash notification - no user location available")
                return false
            }
        }
        
        return true
    }
    
    /**
     * Decode a geohash string to approximate latitude/longitude
     */
    private fun decodeGeohashToLocation(geohash: String): Location? {
        return try {
            val (lat, lon) = Geohash.decodeToCenter(geohash)
            val location = Location("geohash")
            location.latitude = lat
            location.longitude = lon
            location
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding geohash $geohash: ${e.message}")
            null
        }
    }
    
    /**
     * Calculate distance between two locations using Haversine formula
     */
    private fun calculateDistance(location1: Location, location2: Location): Double {
        val earthRadiusKm = 6371.0
        
        val lat1Rad = Math.toRadians(location1.latitude)
        val lat2Rad = Math.toRadians(location2.latitude)
        val deltaLatRad = Math.toRadians(location2.latitude - location1.latitude)
        val deltaLonRad = Math.toRadians(location2.longitude - location1.longitude)
        
        val a = sin(deltaLatRad / 2) * sin(deltaLatRad / 2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(deltaLonRad / 2) * sin(deltaLonRad / 2)
        
        val c = 2 * acos(kotlin.math.sqrt(a))
        
        return earthRadiusKm * c
    }
    
    /**
     * Check if geohash is in a list of problematic ones
     */
    fun isProblematicGeohash(geohash: String): Boolean {
        return PROBLEMATIC_GEOHASHES.any { geohash.startsWith(it) }
    }
    
    /**
     * Get debug information about the filter state
     */
    fun getFilterStats(): Map<String, Any> {
        return mapOf(
            "hasUserLocation" to (lastKnownLocation != null),
            "userLocation" to if (lastKnownLocation != null) {
                "${lastKnownLocation!!.latitude}, ${lastKnownLocation!!.longitude}"
            } else "unknown",
            "maxDistanceKm" to MAX_NOTIFICATION_DISTANCE_KM,
            "problematicGeohashes" to PROBLEMATIC_GEOHASHES.size,
            "lastLocationUpdate" to if (lastKnownLocation != null) {
                lastKnownLocation!!.time
            } else 0L
        )
    }
}