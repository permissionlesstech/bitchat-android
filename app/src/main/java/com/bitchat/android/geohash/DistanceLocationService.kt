package com.bitchat.android.geohash

import android.location.Location
import android.util.Log
import kotlin.math.*

/**
 * Service para localizar pares y dispositivos basado en distancia.
 * Proporciona funciones de cálculo de distancia entre coordenadas usando la fórmula de Haversine.
 * Compatible con el sistema de geohash existente de bitchat.
 */
object DistanceLocationService {
    private const val TAG = "DistanceLocationService"
    
    // Radio de la Tierra en metros
    private const val EARTH_RADIUS_METERS = 6371000.0
    
    /**
     * Coordenadas geográficas de un peer o ubicación
     */
    data class GeoLocation(
        val latitude: Double,
        val longitude: Double,
        val peerId: String? = null,
        val nickname: String? = null,
        val accuracy: Float? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Resultado de búsqueda de pares cercanos
     */
    data class NearbyPeer(
        val peerId: String,
        val nickname: String?,
        val latitude: Double,
        val longitude: Double,
        val distanceMeters: Double,
        val bearing: Float = 0f,
        val accuracy: Float? = null
    )
    
    /**
     * Calcula la distancia entre dos ubicaciones usando la fórmula de Haversine.
     * Esta fórmula es más precisa para distancias cortas y medianas.
     * 
     * @param from Primera ubicación
     * @param to Segunda ubicación
     * @return Distancia en metros
     */
    fun calculateDistance(from: GeoLocation, to: GeoLocation): Double {
        return calculateDistance(
            from.latitude, from.longitude,
            to.latitude, to.longitude
        )
    }
    
    /**
     * Calcula la distancia entre dos puntos en coordenadas lat/lon.
     * 
     * @param lat1 Latitud del primer punto en grados
     * @param lon1 Longitud del primer punto en grados
     * @param lat2 Latitud del segundo punto en grados
     * @param lon2 Longitud del segundo punto en grados
     * @return Distancia en metros
     */
    fun calculateDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        
        val c = 2 * asin(sqrt(a))
        return EARTH_RADIUS_METERS * c
    }
    
    /**
     * Calcula el acimut (bearing) desde el punto "from" hacia el punto "to".
     * El acimut va de 0° a 360°, donde 0° es norte, 90° es este, etc.
     * 
     * @param from Ubicación de origen
     * @param to Ubicación de destino
     * @return Acimut en grados (0-360)
     */
    fun calculateBearing(from: GeoLocation, to: GeoLocation): Float {
        return calculateBearing(
            from.latitude, from.longitude,
            to.latitude, to.longitude
        )
    }
    
    /**
     * Calcula el acimut entre dos puntos en coordenadas lat/lon.
     * 
     * @param lat1 Latitud del primer punto en grados
     * @param lon1 Longitud del primer punto en grados
     * @param lat2 Latitud del segundo punto en grados
     * @param lon2 Longitud del segundo punto en grados
     * @return Acimut en grados (0-360)
     */
    fun calculateBearing(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        
        var bearing = Math.toDegrees(atan2(y, x)).toFloat()
        bearing = (bearing + 360) % 360
        return bearing
    }
    
    /**
     * Filtra una lista de pares cercanos dentro de un radio específico.
     * 
     * @param currentLocation Ubicación actual del usuario
     * @param peers Lista de pares con ubicaciones conocidas
     * @param radiusMeters Radio de búsqueda en metros
     * @return Lista de pares cercanos ordenados por distancia (ascendente)
     */
    fun findNearbyPeers(
        currentLocation: GeoLocation,
        peers: List<GeoLocation>,
        radiusMeters: Double
    ): List<NearbyPeer> {
        return peers
            .mapNotNull { peer ->
                val distance = calculateDistance(currentLocation, peer)
                if (distance <= radiusMeters) {
                    val bearing = calculateBearing(currentLocation, peer)
                    NearbyPeer(
                        peerId = peer.peerId ?: return@mapNotNull null,
                        nickname = peer.nickname,
                        latitude = peer.latitude,
                        longitude = peer.longitude,
                        distanceMeters = distance,
                        bearing = bearing,
                        accuracy = peer.accuracy
                    )
                } else {
                    null
                }
            }
            .sortedBy { it.distanceMeters }
    }
    
    /**
     * Filtra pares cercanos usando un geohash de referencia.
     * Los pares dentro de la misma celda de geohash y sus vecinas se consideran cercanos.
     * 
     * @param myGeohash El geohash de la ubicación actual
     * @param precision Precisión del geohash (número de caracteres)
     * @param peerGeohashes Mapa de peerId -> geohash
     * @return Lista de peerIds que están cerca basado en geohash
     */
    fun findNearbyPeersByGeohash(
        myGeohash: String,
        precision: Int,
        peerGeohashes: Map<String, String>
    ): List<String> {
        // Obtener la celda actual y sus vecinas
        val neighbors = Geohash.neighborsSamePrecision(myGeohash)
        val nearbyGeohashes = neighbors + myGeohash
        
        return peerGeohashes
            .filter { (_, peerGeohash) ->
                // Verificar si el geohash del peer comienza con alguno de los geohashes cercanos
                nearbyGeohashes.any { nearby ->
                    peerGeohash.startsWith(nearby.take(precision))
                }
            }
            .keys
            .toList()
    }
    
    /**
     * Convierte una ubicación de Android a GeoLocation.
     * 
     * @param location Objeto Location de Android
     * @param peerId ID del peer (opcional)
     * @param nickname Apodo del peer (opcional)
     * @return GeoLocation correspondiente
     */
    fun fromAndroidLocation(
        location: Location,
        peerId: String? = null,
        nickname: String? = null
    ): GeoLocation {
        return GeoLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            peerId = peerId,
            nickname = nickname,
            accuracy = location.accuracy,
            timestamp = location.time
        )
    }
    
    /**
     * Determina la descripción de distancia de forma legible.
     * 
     * @param distanceMeters Distancia en metros
     * @return Descripción legible de la distancia
     */
    fun formatDistance(distanceMeters: Double): String {
        return when {
            distanceMeters < 1000 -> "${distanceMeters.toInt()} m"
            distanceMeters < 10000 -> "%.1f km".format(distanceMeters / 1000)
            else -> "%.0f km".format(distanceMeters / 1000)
        }
    }
    
    /**
     * Determina la descripción de dirección (bearing) de forma legible.
     * 
     * @param bearing Acimut en grados (0-360)
     * @return Descripción de la dirección (N, NE, E, SE, S, SW, W, NW)
     */
    fun formatBearing(bearing: Float): String {
        return when {
            bearing < 22.5 || bearing >= 337.5 -> "N"
            bearing < 67.5 -> "NE"
            bearing < 112.5 -> "E"
            bearing < 157.5 -> "SE"
            bearing < 202.5 -> "S"
            bearing < 247.5 -> "SW"
            bearing < 292.5 -> "W"
            else -> "NW"
        }
    }
    
    /**
     * Calcula el polígono (bounding box) de una región circular.
     * Útil para consultas de base de datos espaciales.
     * 
     * @param centerLat Latitud del centro en grados
     * @param centerLon Longitud del centro en grados
     * @param radiusMeters Radio en metros
     * @return Cuatro esquinas del bounding box: (minLat, maxLat, minLon, maxLon)
     */
    fun calculateBoundingBox(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double
    ): Quad<Double, Double, Double, Double> {
        // Aproximación: 1 grado ≈ 111 km en el ecuador
        val latDelta = radiusMeters / 111000.0
        // La delta de longitud depende de la latitud
        val lonDelta = radiusMeters / (111000.0 * cos(Math.toRadians(centerLat)))
        
        return Quad(
            centerLat - latDelta,  // minLat
            centerLat + latDelta,  // maxLat
            centerLon - lonDelta,  // minLon
            centerLon + lonDelta   // maxLon
        )
    }
    
    /**
     * Clase auxiliar para retornar cuatro valores
     */
    data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
    
    /**
     * Verifica si una ubicación está dentro de un radio circular.
     * 
     * @param centerLat Latitud del centro en grados
     * @param centerLon Longitud del centro en grados
     * @param radiusMeters Radio en metros
     * @param testLat Latitud del punto a verificar en grados
     * @param testLon Longitud del punto a verificar en grados
     * @return true si el punto está dentro del radio, false en caso contrario
     */
    fun isWithinRadius(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double,
        testLat: Double,
        testLon: Double
    ): Boolean {
        val distance = calculateDistance(centerLat, centerLon, testLat, testLon)
        return distance <= radiusMeters
    }
    
    /**
     * Calcula el punto intermedio entre dos ubicaciones.
     * Útil para encontrar un punto de encuentro entre dos pares.
     * 
     * @param lat1 Latitud del primer punto en grados
     * @param lon1 Longitud del primer punto en grados
     * @param lat2 Latitud del segundo punto en grados
     * @param lon2 Longitud del segundo punto en grados
     * @return Pair(latitudMedio, longitudMedio)
     */
    fun calculateMidpoint(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Pair<Double, Double> {
        val dLon = Math.toRadians(lon2 - lon1)
        val bx = cos(Math.toRadians(lat2)) * cos(dLon)
        val by = cos(Math.toRadians(lat2)) * sin(dLon)
        
        val lat = atan2(
            sin(Math.toRadians(lat1)) + sin(Math.toRadians(lat2)),
            sqrt(
                (cos(Math.toRadians(lat1)) + bx).pow(2) + by.pow(2)
            )
        )
        val lon = Math.toRadians(lon1) + atan2(by, cos(Math.toRadians(lat1)) + bx)
        
        return Pair(
            Math.toDegrees(lat),
            Math.toDegrees(lon)
        )
    }
}
