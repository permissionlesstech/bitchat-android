# Geohash Presence Specification

## Overview

The Geohash Presence feature provides a mechanism to track online participants in geohash-based location channels without relying on chat message activity. It uses a dedicated ephemeral Nostr event kind to broadcast "heartbeats," ensuring accurate and privacy-preserving online counts.

## Nostr Protocol

### Event Kind
A new ephemeral event kind is defined for presence heartbeats:
- **Kind:** `20001` (`GEOHASH_PRESENCE`)
- **Type:** Ephemeral (not stored by relays long-term)

### Event Structure
The presence event mimics the structure of a geohash chat message (Kind 20000) but without content or nickname metadata, to minimize overhead and focus purely on "liveness".

```json
{
  "kind": 20001,
  "created_at": <timestamp>,
  "tags": [
    ["g", "<geohash>"]
  ],
  "content": "",
  "pubkey": "<geohash_derived_pubkey>",
  "id": "<event_id>",
  "sig": "<signature>"
}
```

*   **`content`**: Must be empty string.
*   **`tags`**: Must include `["g", "<geohash>"]`. Should NOT include `["n", "<nickname>"]`.
*   **`pubkey`**: The ephemeral identity derived specifically for this geohash (same as used for chat messages).

## Client Behavior

### 1. Broadcasting Presence

Clients MUST broadcast a Kind 20001 presence event in the following scenarios:

*   **Sampling Mode (Sheet Open):**
    *   When the user opens the list of nearby/bookmarked location channels.
    *   **Frequency:** Immediately upon opening, and repeated every **60 seconds** while the list remains open.
    *   **Scope:** Sent to *all* geohashes currently being sampled (nearby + bookmarks).

*   **Active Chat Mode (Channel Open):**
    *   When the user enters a specific geohash chat channel.
    *   **Frequency:** Immediately upon entry, and repeated every **60 seconds** while the user remains in the channel.
    *   **Scope:** Sent to the *current* geohash only.

### 2. Subscribing to Presence

Clients must update their Nostr filters to listen for both chat and presence events on geohash channels.

*   **Filter:**
    *   `kinds`: `[20000, 20001]`
    *   `#g`: `["<geohash>"]`

### 3. Participant Counting

The "online participants" count shown in the UI must be derived **exclusively** from Kind 20001 events.

*   **Logic:**
    *   Maintain a map of `pubkey -> last_seen_timestamp` for each geohash.
    *   Update `last_seen_timestamp` *only* upon receiving a valid Kind 20001 event.
    *   Kind 20000 (Chat) events should **not** update the "online" timestamp for counting purposes (though they may update nickname caches).
    *   A participant is considered "online" if their `last_seen_timestamp` is within the last **5 minutes**.

### 4. Implementation Details (Android)

*   **`NostrKind.GEOHASH_PRESENCE`**: Added constant `20001`.
*   **`NostrProtocol.createGeohashPresenceEvent`**: Helper to generate the event.
*   **`GeohashViewModel`**:
    *   Manages `samplingPresenceJob` for the location sheet.
    *   Manages `activePresenceJob` for the active chat channel.
    *   Both jobs run a `while(true)` loop with `delay(60000)`.
*   **`GeohashMessageHandler`**:
    *   Refactored to handle `GEOHASH_PRESENCE`.
    *   Updates `GeohashRepository` participant timestamps only on Kind 20001.

## Benefits

*   **Accuracy:** Counts reflect active listeners, not just speakers.
*   **Efficiency:** Small payload size (empty content).
*   **Privacy:** No nickname broadcast in heartbeats (nicknames only revealed when speaking).
