# Tor Library API Migration Guide

## From arti-mobile-ex to tor-android

This guide helps you migrate your Tor integration code from the old `arti-mobile-ex:1.2.3` library to the new `tor-android:0.4.8.13` library that supports 16KB page size.

## Key Changes

### 1. Package Name Changes
```kotlin
// OLD (arti-mobile-ex)
import org.torproject.arti.mobile.*

// NEW (tor-android)
import org.torproject.android.*
// or
import info.guardianproject.netcipher.proxy.*
```

### 2. Initialization Changes

#### Old Arti Mobile Ex Pattern:
```kotlin
// OLD - This will no longer work
val artiConfig = ArtiConfig.builder()
    .dataDirectory(torDataDir)
    .build()
    
val artiClient = ArtiClient.create(artiConfig)
```

#### New Tor Android Pattern:
```kotlin
// NEW - Use TorService or OrbotHelper
import info.guardianproject.netcipher.proxy.OrbotHelper
import org.torproject.android.service.TorService

// Option 1: Using OrbotHelper (recommended)
if (OrbotHelper.isOrbotInstalled(context)) {
    OrbotHelper.requestStartTor(context)
} else {
    // Handle Orbot not installed
}

// Option 2: Direct TorService integration
val intent = Intent(context, TorService::class.java)
context.startService(intent)
```

### 3. Proxy Configuration

#### Old Pattern:
```kotlin
// OLD
val proxy = artiClient.createSocksProxy()
```

#### New Pattern:
```kotlin
// NEW
import java.net.Proxy
import java.net.InetSocketAddress

val torProxy = Proxy(
    Proxy.Type.SOCKS,
    InetSocketAddress("127.0.0.1", 9050)
)
```

### 4. HTTP Client Configuration (OkHttp)

#### Updated OkHttp Configuration:
```kotlin
import okhttp3.OkHttpClient
import java.net.Proxy
import java.net.InetSocketAddress

val torProxy = Proxy(
    Proxy.Type.SOCKS, 
    InetSocketAddress("127.0.0.1", 9050)
)

val torEnabledClient = OkHttpClient.Builder()
    .proxy(torProxy)
    .build()
```

## Migration Steps

### Step 1: Update Dependencies
✅ Already done - `tor-android:0.4.8.13` is now in your build.gradle.kts

### Step 2: Find Your Tor Integration Code
Look for files containing:
- `arti` imports
- `ArtiClient` usage
- `ArtiConfig` setup
- SOCKS proxy creation

### Step 3: Replace Imports
```bash
# Search for arti imports in your project
find app/src -name "*.kt" -exec grep -l "arti" {} \;
```

### Step 4: Update Initialization Code
Replace arti-specific initialization with standard Tor proxy setup.

### Step 5: Test Connectivity
Ensure your app can still connect through Tor after the changes.

## Common Migration Patterns

### WebSocket over Tor
```kotlin
// NEW approach for WebSocket over Tor
val torProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050))
val client = OkHttpClient.Builder()
    .proxy(torProxy)
    .build()

val request = Request.Builder()
    .url("ws://your-onion-service.onion")
    .build()
    
val webSocket = client.newWebSocket(request, webSocketListener)
```

### Nostr Relay Connections
```kotlin
// For Nostr relay connections over Tor
val torClient = OkHttpClient.Builder()
    .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050)))
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()
```

## Verification Steps

1. **Build Check**: Ensure your project builds without errors
2. **Import Check**: No more `arti` imports should exist
3. **Runtime Check**: Test Tor connectivity in your app
4. **Performance Check**: Verify connection times are acceptable

## Troubleshooting

### Issue: Tor Proxy Not Available
```kotlin
// Add connection checks
private fun isTorAvailable(): Boolean {
    return try {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", 9050), 1000)
        socket.close()
        true
    } catch (e: Exception) {
        false
    }
}
```

### Issue: Slow Connections
```kotlin
// Increase timeouts for Tor
val torClient = OkHttpClient.Builder()
    .proxy(torProxy)
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()
```

## Need Help?

If you encounter issues during migration:
1. Check the [Tor Android documentation](https://github.com/torproject/tor-android)
2. Review Guardian Project's NetCipher library
3. Test with a simple HTTP request first before complex WebSocket connections

## Testing Your Migration

Run this verification:
```bash
./gradlew verify16KBPageSizeSupport
```

This should show:
- ✅ Updated to tor-android:0.4.8.13 (16KB compatible)
- No 16KB alignment warnings
