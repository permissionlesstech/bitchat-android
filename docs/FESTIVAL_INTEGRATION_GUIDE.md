# Android Porting Guide - Festivus Mestivus

This guide explains how to integrate the festival features with a fork of [bitchat-android](https://github.com/permissionlesstech/bitchat-android).

## Architecture Overview

### iOS → Android Mapping

| iOS (Swift/SwiftUI) | Android (Kotlin/Compose) | File |
|---------------------|--------------------------|------|
| `FestivalModels.swift` | `FestivalModels.kt` | Data classes |
| `FestivalScheduleManager` | `FestivalScheduleManager.kt` | ViewModel |
| `FestivalModeManager` | `FestivalModeManager.kt` | Persistence |
| `FestivalContentView.swift` | `FestivalScreen.kt` | Main UI |
| `FestivalScheduleView.swift` | `FestivalScheduleView.kt` | Schedule tab |
| `FestivalSchedule.json` | Same file (shared) | Data |

### SwiftUI → Compose Cheat Sheet

```swift
// SwiftUI                      // Compose
VStack { }                      Column { }
HStack { }                      Row { }
ZStack { }                      Box { }
List { }                        LazyColumn { }
ScrollView { }                  Modifier.verticalScroll()
@State var                      remember { mutableStateOf() }
@Published var                  MutableStateFlow<T>
@ObservedObject                 collectAsState()
NavigationView                  NavHost
TabView                         Scaffold + BottomNavigation
```

## Integration Steps

### Step 1: Fork bitchat-android

```bash
# Fork via GitHub UI, then clone
git clone https://github.com/YOUR_USERNAME/bitchat-android.git
cd bitchat-android
```

### Step 2: Copy Festival Features

```bash
# From the festivus-mestivus repo
cp -r android/app/src/main/java/com/bitchat/android/features/festival \
      /path/to/bitchat-android/app/src/main/java/com/bitchat/android/features/
```

### Step 3: Copy Shared Assets

The `FestivalSchedule.json` is shared between platforms:

```bash
mkdir -p app/src/main/assets/festival
cp bitchat/Features/festival/FestivalSchedule.json \
   app/src/main/assets/festival/
```

### Step 4: Add Dependencies

In `app/build.gradle.kts`:

```kotlin
plugins {
    // ... existing plugins
    kotlin("plugin.serialization") version "1.9.20"
}

dependencies {
    // JSON parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    
    // Google Maps for festival map (optional)
    implementation("com.google.maps.android:maps-compose:4.3.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
}
```

### Step 5: Integrate with MainActivity

Modify `MainActivity.kt` to show festival mode when enabled:

```kotlin
@Composable
fun MainContent(chatViewModel: ChatViewModel) {
    val context = LocalContext.current
    val festivalModeManager = remember { FestivalModeManager.getInstance(context) }
    val isFestivalMode by festivalModeManager.isFestivalModeEnabled.collectAsState()
    
    if (isFestivalMode) {
        FestivalScreen(
            // Pass chatViewModel for the chat tab integration
        )
    } else {
        // Existing ChatScreen
        ChatScreen(viewModel = chatViewModel)
    }
}
```

### Step 6: Add Festival Mode Toggle

In the settings/debug sheet, add a toggle:

```kotlin
@Composable
fun SettingsContent() {
    val context = LocalContext.current
    val festivalModeManager = remember { FestivalModeManager.getInstance(context) }
    val isFestivalMode by festivalModeManager.isFestivalModeEnabled.collectAsState()
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Festival Mode", style = MaterialTheme.typography.titleMedium)
            Text(
                "Enable Festivus Mestivus features",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = isFestivalMode,
            onCheckedChange = { festivalModeManager.setFestivalModeEnabled(it) }
        )
    }
}
```

### Step 7: Stage Auto-Join (Optional)

To auto-join stage channels based on location, integrate with the existing location system:

```kotlin
// In your location callback
fun onLocationUpdate(location: Location) {
    val festivalManager = FestivalScheduleManager(application)
    val nearestStage = festivalManager.nearestStageWithinDistance(location, 100f)
    
    nearestStage?.let { stage ->
        if (currentChannel != stage.channelName) {
            // Join the stage channel
            chatViewModel.joinChannel(stage.channelName)
            festivalModeManager.lastJoinedChannel = stage.channelName
        }
    }
}
```

## Testing Checklist

- [ ] JSON loads from assets correctly
- [ ] Schedule shows all 3 festival days
- [ ] Day selector switches between days
- [ ] Stage filter works
- [ ] "Now Playing" updates (mock time or wait for actual times)
- [ ] Festival mode toggle persists across app restart
- [ ] Chat tab shows existing bitchat functionality
- [ ] Map tab shows stage markers (if implemented)
- [ ] Custom channels display with correct icons/colors

## Customizing for Your Festival

Edit `FestivalSchedule.json` to update:
- Festival name, dates, location
- Stages (with geohash for auto-join)
- Sets/lineup
- Custom channels
- Points of interest

The same JSON works on both iOS and Android.

## Troubleshooting

### JSON parsing fails
- Check that kotlinx-serialization plugin is applied
- Verify JSON is valid (use jsonlint.com)
- Check asset path: `assets/festival/FestivalSchedule.json`

### Festival mode doesn't persist
- Ensure `FestivalModeManager.getInstance(context)` uses application context
- Check SharedPreferences isn't being cleared elsewhere

### Map doesn't show
- Add Google Maps API key to `AndroidManifest.xml`
- Enable Maps SDK in Google Cloud Console
