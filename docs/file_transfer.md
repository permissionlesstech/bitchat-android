# Bitchat Bluetooth File Transfer: Audio + Images

This document is the exhaustive implementation guide for Bitchat’s Bluetooth file transfer for voice notes (audio) and images. It describes the on‑wire packet format, fragmentation/progress/cancellation, sender/receiver behaviors, and the UX we implemented in the Android client so that other implementers can interoperate and match the user experience precisely.

The guide is organized into:

- Protocol overview (BitchatPacket + File Transfer payload)
- Fragmentation, progress reporting, and cancellation
- Receive path, validation, and persistence
- Sender path (audio + images)
- UI/UX behavior (recording, sending, playback, image rendering)
- File inventory (source files and their roles)


---

## 1) Protocol Overview

Bitchat BLE transport carries application messages inside the common `BitchatPacket` envelope. File transfer reuses the same envelope as public and private messages, with a distinct `type` and a TLV‑encoded payload.

### 1.1 BitchatPacket envelope

Fields (subset relevant to file transfer):

- `version: UByte` — protocol version (currently `1`).
- `type: UByte` — message type. File transfer uses `MessageType.FILE_TRANSFER (0x22)`.
- `senderID: ByteArray (8)` — 8‑byte binary peer ID.
- `recipientID: ByteArray (8)` — 8‑byte recipient. For public: `SpecialRecipients.BROADCAST (0xFF…FF)`; for private: the target peer’s 8‑byte ID.
- `timestamp: ULong` — milliseconds since epoch.
- `payload: ByteArray` — TLV file payload (see below).
- `signature: ByteArray?` — optional signature (present for private sends in our implementation, to match iOS integrity path).
- `ttl: UByte` — hop TTL (we use `MAX_TTL` for broadcast, `7` for private).

Envelope creation and broadcast paths are implemented in:

- `app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt)
- `app/src/main/java/com/bitchat/android/mesh/BluetoothConnectionManager.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/mesh/BluetoothConnectionManager.kt)
- `app/src/main/java/com/bitchat/android/mesh/PacketProcessor.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/mesh/PacketProcessor.kt)

Private sends are additionally encrypted at the higher layer (Noise) for text messages, but file transfers use the `FILE_TRANSFER` message type in the clear at the envelope level with content carried inside a TLV. See code for any deployment‑specific enforcement.

### 1.2 File Transfer TLV payload (BitchatFilePacket)

The file payload is a compact TLV structure with 2‑byte big‑endian length fields.

- Defined in `app/src/main/java/com/bitchat/android/model/BitchatFilePacket.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/model/BitchatFilePacket.kt)

TLVs:

- `0x01 FILE_NAME` — UTF‑8 bytes (≤ 65535 bytes)
- `0x02 FILE_SIZE` — 8 bytes (UInt64, big‑endian)
- `0x03 MIME_TYPE` — UTF‑8 bytes (≤ 65535 bytes). Examples: `audio/mp4`, `image/jpeg`.
- `0x04 CONTENT` — raw file bytes (≤ 65535 bytes)

Encoding rules:

- Each TLV: 1 byte type + 2 bytes length (big‑endian) + value.
- For `FILE_SIZE`, length must be `8`. For other TLVs, length can be any value up to `0xFFFF`.
- The total payload should remain ≤ 64 KiB to stay within fragmentation constraints for BLE.
- Implementations should validate TLV boundaries; decoding returns `null` if malformed.

Decoding rules:

- Accumulate TLVs in a loop. Unknown types should cause fail‑fast (current code only accepts the 4 types above).
- `fileSize` falls back to `content.size` on decode if missing.
- `mimeType` defaults to `application/octet-stream` if missing.
- The result is `(fileName, fileSize, mimeType, content)`.


---

## 2) Fragmentation, Progress, and Cancellation

### 2.1 Fragmentation

File transfers reuse the mesh broadcaster’s fragmentation logic:

- `BluetoothPacketBroadcaster` checks if the serialized envelope exceeds the configured MTU and splits it into fragments via `FragmentManager`.
- Fragments are sent with a short inter‑fragment delay (currently ~200 ms; matches iOS/Rust behavior notes in code).
- When only one fragment is needed, send as a single packet.

### 2.2 Transfer ID and progress events

We derive a deterministic transfer ID to track progress:

- `transferId = sha256Hex(packet.payload)` (hex string of the file TLV payload).

The broadcaster emits progress events to a shared flow:

- `TransferProgressManager.start(id, totalFragments)`
- `TransferProgressManager.progress(id, sent, totalFragments)`
- `TransferProgressManager.complete(id, totalFragments)`

The UI maps `transferId → messageId`, then updates `DeliveryStatus.PartiallyDelivered(sent, total)` as events arrive; when `complete`, switches to `Delivered`.

### 2.3 Cancellation

Transfers are cancellable mid‑flight:

- The broadcaster keeps a `transferId → Job` map and cancels the job to stop sending remaining fragments.
- API path:
  - `BluetoothPacketBroadcaster.cancelTransfer(transferId)`
  - Exposed via `BluetoothConnectionManager.cancelTransfer` and `BluetoothMeshService.cancelFileTransfer`.
  - `ChatViewModel.cancelMediaSend(messageId)` resolves `messageId → transferId` and cancels.
- UX: tapping the “X” on a sending media removes the message from the timeline immediately.

Implementation files:

- `app/src/main/java/com/bitchat/android/mesh/BluetoothPacketBroadcaster.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/mesh/BluetoothPacketBroadcaster.kt)
- `app/src/main/java/com/bitchat/android/mesh/BluetoothConnectionManager.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/mesh/BluetoothConnectionManager.kt)
- `app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt)
- `app/src/main/java/com/bitchat/android/ui/ChatViewModel.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/ChatViewModel.kt)


---

## 3) Receive Path and Persistence

Receiver dispatch is in `MessageHandler`:

- For both broadcast and private paths we try `BitchatFilePacket.decode(payload)`. If it decodes:
  - The file is persisted under app files with type‑specific subfolders:
    - Audio: `files/voicenotes/incoming/`
    - Image: `files/images/incoming/`
  - We always generate a unique filename on receive (timestamp‑based) to avoid collisions.
  - MIME determines extension (`.m4a`, `.jpg`, `.png`, `.webp`, etc.).
- A synthetic chat message is created with content markers pointing to the local path:
  - Audio: `"[voice] /abs/path/to/file"`
  - Image: `"[image] /abs/path/to/file"`
  - `senderPeerID` is set to the origin, `isPrivate` set appropriately.

Files:

- `app/src/main/java/com/bitchat/android/mesh/MessageHandler.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/mesh/MessageHandler.kt)


---

## 4) Sender Path

### 4.1 Audio (Voice Notes)

1) Capture
   - Hold‑to‑record mic button starts `MediaRecorder` with AAC in MP4 (`audio/mp4`).
   - Sample rate: 44100 Hz, channels: mono, bitrate: ~32 kbps (to reduce payload size for BLE).
   - On release, we pad 500 ms before stopping to avoid clipping endings.
   - Files saved under `files/voicenotes/outgoing/voice_YYYYMMDD_HHMMSS.m4a`.

2) Local echo
   - We create a `BitchatMessage` with content `"[voice] <path>"` and add to the appropriate timeline (public/channel/private).
   - For private: `messageManager.addPrivateMessage(peerID, message)`. For public/channel: `messageManager.addMessage(message)` or add to channel.

3) Packet creation
   - Build a `BitchatFilePacket`:
     - `fileName`: basename (e.g., `voice_… .m4a`)
     - `fileSize`: file length
     - `mimeType`: `audio/mp4`
     - `content`: full bytes (ensure content ≤ 64 KiB; with chosen codec params typical short notes fit fragmentation constraints)
   - Encode TLV; compute `transferId = sha256Hex(payload)`.
   - Map `transferId → messageId` for UI progress.

4) Send
   - Public: `BluetoothMeshService.sendFileBroadcast(filePacket)`.
   - Private: `BluetoothMeshService.sendFilePrivate(peerID, filePacket)`.
   - Broadcaster handles fragmentation and progress emission.

5) Waveform
   - We extract a 120‑bin waveform from the recorded file (the same extractor used for the receiver) and cache by file path, so sender and receiver waveforms are identical.

Core files:

- `app/src/main/java/com/bitchat/android/ui/ChatViewModel.kt` (sendVoiceNote) (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/ChatViewModel.kt)
- `app/src/main/java/com/bitchat/android/model/BitchatFilePacket.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/model/BitchatFilePacket.kt)
- `app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt)
- `app/src/main/java/com/bitchat/android/features/voice/VoiceRecorder.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/features/voice/VoiceRecorder.kt)
- `app/src/main/java/com/bitchat/android/features/voice/Waveform.kt` (cache + extractor) (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/features/voice/Waveform.kt)

### 4.2 Images

1) Selection and processing
   - System picker (Storage Access Framework) with `GetContent()` (`image/*`). No storage permission required.
   - Selected image is downscaled so longest edge is 512 px; saved as JPEG (85% quality) under `files/images/outgoing/img_<timestamp>.jpg`.
   - Helper: `ImageUtils.downscaleAndSaveToAppFiles(context, uri, maxDim=512)`.

2) Local echo
   - Insert a message with `"[image] <path>"` in the current context (public/channel/private).

3) Packet creation
   - Build `BitchatFilePacket` with mime `image/jpeg` and file content.
   - Encode TLV + compute `transferId` and map to `messageId`.

4) Send
   - Same paths as audio (broadcast/private), including fragmentation and progress emission.

Core files:

- `app/src/main/java/com/bitchat/android/features/media/ImageUtils.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/features/media/ImageUtils.kt)
- `app/src/main/java/com/bitchat/android/ui/ChatViewModel.kt` (sendImageNote) (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/ChatViewModel.kt)
- `app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt)


---

## 5) UI / UX Details

This section specifies exactly what users see and how inputs behave, so alternative clients can match the experience.

### 5.1 Message input area

- The input field remains mounted at all times to prevent the IME (keyboard) from collapsing during long‑press interactions (recording). We overlay recording UI atop the text field rather than replacing it.
- While recording, the text caret (cursor) is hidden by setting a transparent cursor brush.
- Mentions and slash commands are styled with a monospace look and color coding.

Files:

- `app/src/main/java/com/bitchat/android/ui/InputComponents.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/InputComponents.kt)

### 5.2 Recording UX

- Hold the mic button to start recording. Recording runs until release, then we pad 500 ms and stop.
- While recording, a dense, real‑time scrolling waveform overlays the input showing live audio; a timer is shown to the right.
  - Component: `RealtimeScrollingWaveform` (dense bars, ~240 columns, ~20 FPS) in `app/src/main/java/com/bitchat/android/ui/media/RealtimeScrollingWaveform.kt`.
  - The keyboard stays visible; the caret is hidden.
- On release, we immediately show a local echo message for the voice note and start sending.

### 5.3 Voice note rendering

- Displayed with a header (nickname + timestamp) then the waveform + controls row.
- Waveform
  - A 120‑bin static waveform is rendered per file, identical for sender and receiver, extracted from the actual audio file.
  - During send, the waveform fills left→right in blue based on fragment progress.
  - During playback, the waveform fills left→right in green based on player progress.
- Controls
  - Play/Pause toggle to the left of the waveform; duration text to the right.
- Cancel sending
  - While sending a voice note, a round “X” cancel button appears to the right of the controls. Tapping cancels the transfer mid‑flight.

Files:

- `app/src/main/java/com/bitchat/android/ui/MessageComponents.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/MessageComponents.kt)
- `app/src/main/java/com/bitchat/android/ui/media/WaveformViews.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/media/WaveformViews.kt)
- `app/src/main/java/com/bitchat/android/features/voice/Waveform.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/features/voice/Waveform.kt)

### 5.4 Image sending UX

- A circular “+” button next to the mic opens the system image picker. After selection, we downscale to 512 px longest edge and show a local echo; the send begins immediately.
- Progress visualization
  - Instead of a linear progress bar, we reveal the image block‑by‑block (modem‑era homage).
  - The image is divided into a constant grid (default 24×16), and the blocks are rendered in order based on fragment progress; there are no gaps between tiles.
  - The cancel “X” button overlays the top‑right corner during sending.
- On cancel, the message is removed from the chat immediately.

Files:

- `app/src/main/java/com/bitchat/android/ui/media/ImagePickerButton.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/media/ImagePickerButton.kt)
- `app/src/main/java/com/bitchat/android/features/media/ImageUtils.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/features/media/ImageUtils.kt)
- `app/src/main/java/com/bitchat/android/ui/media/BlockRevealImage.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/media/BlockRevealImage.kt)
- `app/src/main/java/com/bitchat/android/ui/MessageComponents.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/MessageComponents.kt)

### 5.5 Image receiving UX

- Received images render fully with rounded corners and are left‑aligned like text messages.
- Tapping an image opens a fullscreen viewer with an option to save to the device Downloads via `MediaStore`.

Files:

- `app/src/main/java/com/bitchat/android/ui/media/FullScreenImageViewer.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/media/FullScreenImageViewer.kt)


---

## 6) Edge Cases and Notes

- Filename collisions on receiver: we always generate a unique filename, ignoring the sender’s name to avoid overwriting if identical names are reused.
- Path markers in messages
  - We use simple content markers: `"[voice] <abs path>"` and `"[image] <abs path>"` for local rendering. These are not sent on the wire; the actual file bytes are inside the TLV payload.
- Progress math for images relies on `(sent / total)` from `TransferProgressManager` (fragment‑level granularity). The block grid density can be tuned; currently 24×16.
- Private vs public: both use the same file TLV; only the envelope `recipientID` differs. Private may have signatures; code shows a signing step consistent with iOS behavior prior to broadcast to ensure integrity.
- BLE timing: there is a 200 ms inter‑fragment delay for stability. Adjust as needed for your radio stack while maintaining compatibility.


---

## 7) File Inventory (Added/Changed)

Core protocol and transport:

- `app/src/main/java/com/bitchat/android/model/BitchatFilePacket.kt` — TLV payload model + encode/decode. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/model/BitchatFilePacket.kt)
- `app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt` — packet creation and broadcast for file messages. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt)
- `app/src/main/java/com/bitchat/android/mesh/BluetoothPacketBroadcaster.kt` — fragmentation, progress, cancellation via transfer jobs. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/mesh/BluetoothPacketBroadcaster.kt)
- `app/src/main/java/com/bitchat/android/mesh/TransferProgressManager.kt` — progress events bus. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/mesh/TransferProgressManager.kt)
- `app/src/main/java/com/bitchat/android/mesh/MessageHandler.kt` — receive path: decode, persist to files, create chat messages. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/mesh/MessageHandler.kt)

Audio capture and waveform:

- `app/src/main/java/com/bitchat/android/features/voice/VoiceRecorder.kt` — MediaRecorder wrapper. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/features/voice/VoiceRecorder.kt)
- `app/src/main/java/com/bitchat/android/features/voice/Waveform.kt` — cache + extractor + resampler. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/features/voice/Waveform.kt)
- `app/src/main/java/com/bitchat/android/ui/media/WaveformViews.kt` — Compose waveform preview components. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/media/WaveformViews.kt)

Image pipeline:

- `app/src/main/java/com/bitchat/android/features/media/ImageUtils.kt` — downscale and save to app files. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/features/media/ImageUtils.kt)
- `app/src/main/java/com/bitchat/android/ui/media/ImagePickerButton.kt` — SAF picker button. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/media/ImagePickerButton.kt)
- `app/src/main/java/com/bitchat/android/ui/media/BlockRevealImage.kt` — block‑reveal progress renderer (no gaps, dense grid). (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/media/BlockRevealImage.kt)

Recording overlay:

- `app/src/main/java/com/bitchat/android/ui/media/RealtimeScrollingWaveform.kt` — dense, real‑time scrolling waveform during recording. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/media/RealtimeScrollingWaveform.kt)

UI composition and view model coordination:

- `app/src/main/java/com/bitchat/android/ui/InputComponents.kt` — input field, overlays (recording), picker button, mic. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/InputComponents.kt)
- `app/src/main/java/com/bitchat/android/ui/MessageComponents.kt` — message rendering for text/audio/images including progress UIs and cancel overlays. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/MessageComponents.kt)
- `app/src/main/java/com/bitchat/android/ui/ChatViewModel.kt` — sendVoiceNote/sendImageNote, progress mapping, cancelMediaSend. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/ChatViewModel.kt)
- `app/src/main/java/com/bitchat/android/ui/MessageManager.kt` — add/remove/update messages across main, private, and channels. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/MessageManager.kt)

Fullscreen image:

- `app/src/main/java/com/bitchat/android/ui/media/FullScreenImageViewer.kt` — fullscreen viewer + save to Downloads. (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/media/FullScreenImageViewer.kt)


---

## 8) Implementation Checklist for Other Clients

1. Implement `BitchatFilePacket` TLV exactly as specified (type bytes, length as 2‑byte big‑endian, size = 8 for FILE_SIZE).
2. Embed the TLV into a `BitchatPacket` envelope with `type = FILE_TRANSFER (0x22)` and the correct `recipientID` (broadcast vs private).
3. Fragment, send, and report progress using a transfer ID derived from `sha256(payload)` so the UI can map progress to a message.
4. Support cancellation at the fragment sender: stop sending remaining fragments and propagate a cancel to the UI (we remove the message).
5. On receive, decode TLV, persist to an app directory (separate audio/images), and create a chat message with content marker `"[voice] path"` or `"[image] path"` for local rendering.
6. Audio sender and receiver should use the same waveform extractor so visuals match; a 120‑bin histogram is a good balance.
7. For images, optionally downscale to keep TLV under ~64 KiB; JPEG 85% at 512 px longest edge is a good baseline.
8. Mirror the UX:
   - Recording overlay that does not collapse the IME; hide the caret while recording; add 500 ms end padding.
   - Voice: waveform fill for send/playback; cancel overlay.
   - Images: dense block‑reveal with no gaps during sending; cancel overlay; fullscreen viewer with save.

Following the above should produce an interoperable and matching experience across platforms.
