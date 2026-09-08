# Servicio de Localización por Distancia - DistanceLocationService

## Descripción General

`DistanceLocationService` es un servicio de geolocalización que permite:

- **Calcular distancias** entre dos puntos usando la fórmula de Haversine
- **Encontrar pares cercanos** dentro de un radio específico
- **Calcular direcciones** (acimut/bearing) entre ubicaciones
- **Integración con geohash** para búsquedas espaciales eficientes
- **Formateo de distancias** en formato legible

## Uso Básico

### Calcular Distancia Entre Dos Puntos

```kotlin
val distance = DistanceLocationService.calculateDistance(
    lat1 = 40.7128,  // NYC
    lon1 = -74.0060,
    lat2 = 34.0522,  // LA
    lon2 = -118.2437
)
// Resultado: ~3,944,000 metros
```

### Crear una Ubicación

```kotlin
val myLocation = DistanceLocationService.GeoLocation(
    latitude = 40.7128,
    longitude = -74.0060,
    peerId = "my-peer-id",
    nickname = "Alice",
    accuracy = 50f, // en metros
    timestamp = System.currentTimeMillis()
)

// Convertir desde Android Location
val androidLocation = // ... obtener del LocationManager
val geoLocation = DistanceLocationService.fromAndroidLocation(
    location = androidLocation,
    peerId = "my-peer-id",
    nickname = "Alice"
)
```

### Encontrar Pares Cercanos

```kotlin
val peerLocations = listOf(
    DistanceLocationService.GeoLocation(
        latitude = 40.7200,
        longitude = -74.0050,
        peerId = "peer1",
        nickname = "Bob"
    ),
    DistanceLocationService.GeoLocation(
        latitude = 34.0522,
        longitude = -118.2437,
        peerId = "peer2",
        nickname = "Charlie"
    )
)

val nearby = DistanceLocationService.findNearbyPeers(
    currentLocation = myLocation,
    peers = peerLocations,
    radiusMeters = 5000.0 // 5 km
)

// Resultado: Lista de NearbyPeer ordenada por distancia
for (peer in nearby) {
    println("${peer.nickname}: ${peer.distanceMeters}m - Dirección: ${peer.bearing}°")
}
```

### Calcular Acimut (Bearing)

```kotlin
val bearing = DistanceLocationService.calculateBearing(
    lat1 = 40.7128,
    lon1 = -74.0060,
    lat2 = 40.7200,
    lon2 = -74.0050
)
// 0° = Norte, 90° = Este, 180° = Sur, 270° = Oeste
```

### Formatear Distancias

```kotlin
println(DistanceLocationService.formatDistance(500.0))   // "500 m"
println(DistanceLocationService.formatDistance(1500.0))  // "1.5 km"
println(DistanceLocationService.formatDistance(50000.0)) // "50.0 km"

// Formatear direcciones
println(DistanceLocationService.formatBearing(0f))   // "N"
println(DistanceLocationService.formatBearing(45f))  // "NE"
println(DistanceLocationService.formatBearing(90f))  // "E"
```

## Integración con Geohash

El servicio se integra con el sistema de geohash existente para búsquedas espaciales eficientes:

```kotlin
val nearbyByGeohash = DistanceLocationService.findNearbyPeersByGeohash(
    myGeohash = "u4pruydq", // Mi geohash actual
    precision = 8,
    peerGeohashes = mapOf(
        "peer1" to "u4pruydq", // Misma celda
        "peer2" to "u4pruyek", // Celda vecina
        "peer3" to "u4pqzz00"  // Celda lejana
    )
)
// Resultado: ["peer1", "peer2"]
```

## Funciones Avanzadas

### Verificar si un Punto Está Dentro de un Radio

```kotlin
val isClose = DistanceLocationService.isWithinRadius(
    centerLat = 40.7128,
    centerLon = -74.0060,
    radiusMeters = 5000.0,
    testLat = 40.7200,
    testLon = -74.0050
)
// true si está dentro del radio, false en caso contrario
```

### Calcular Bounding Box

```kotlin
val (minLat, maxLat, minLon, maxLon) = DistanceLocationService.calculateBoundingBox(
    centerLat = 40.7128,
    centerLon = -74.0060,
    radiusMeters = 5000.0
)
// Útil para consultas de base de datos espaciales
```

### Encontrar Punto Intermedio

```kotlin
val (midLat, midLon) = DistanceLocationService.calculateMidpoint(
    lat1 = 40.7128,
    lon1 = -74.0060,
    lat2 = 34.0522,
    lon2 = -118.2437
)
// Punto de encuentro entre dos pares
```

## Estructura de Datos

### GeoLocation
```kotlin
data class GeoLocation(
    val latitude: Double,           // Latitud en grados
    val longitude: Double,          // Longitud en grados
    val peerId: String? = null,     // ID del peer (opcional)
    val nickname: String? = null,   // Apodo del peer (opcional)
    val accuracy: Float? = null,    // Precisión de GPS en metros (opcional)
    val timestamp: Long = ...       // Timestamp de la ubicación
)
```

### NearbyPeer
```kotlin
data class NearbyPeer(
    val peerId: String,             // ID del peer
    val nickname: String?,          // Apodo del peer
    val latitude: Double,           // Latitud en grados
    val longitude: Double,          // Longitud en grados
    val distanceMeters: Double,     // Distancia en metros
    val bearing: Float = 0f,        // Acimut en grados (0-360)
    val accuracy: Float? = null     // Precisión de GPS
)
```

## Precisión y Limitaciones

### Fórmula de Haversine
- **Precisión**: Muy precisa para distancias cortas y medianas (< 500 km)
- **Supuestos**: Asume la Tierra como una esfera perfecta
- **Variaciones**: La Tierra es un esferoide, lo que puede causar errores hasta del 0.5%

### Geohash
- **Precisión**: Depende del número de caracteres
  - 4 caracteres: ~20 km
  - 6 caracteres: ~1.2 km
  - 8 caracteres: ~150 m
  - 10 caracteres: ~19 m

## Consideraciones de Privacidad

⚠️ **Importante**: Este servicio maneja datos de ubicación que son sensibles desde el punto de vista de privacidad.

- Las ubicaciones se procesan localmente en el dispositivo
- No se envían ubicaciones exactas a servidores centrales
- Se utiliza geohash para aproximación espacial en lugar de coordenadas exactas
- Los datos de ubicación se cifran en tránsito

## Pruebas Unitarias

Ejecutar las pruebas:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.bitchat.android.geohash.DistanceLocationServiceTest'
```

Las pruebas verifican:
- Cálculo correcto de distancias
- Cálculo de acimut en direcciones cardinales
- Filtrado de pares cercanos
- Formato legible de distancias y direcciones
- Verificación de puntos dentro de radios
- Cálculo de puntos intermedios
