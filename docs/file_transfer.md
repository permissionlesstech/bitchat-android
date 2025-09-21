# Bitchat Bluetooth File Transfer: Images, Audio, and Generic Files (with Interactive Features)

This document is the exhaustive implementation guide for Bitchat’s Bluetooth file transfer protocol for voice notes (audio) and images, including interactive features like waveform seeking. It describes the on‑wire packet format (both v1 and v2), fragmentation/progress/cancellation, sender/receiver behaviors, and the complete UX we implemented in the Android client so that other implementers can interoperate and match the user experience precisely.

**Protocol Versions:**
- **v1**: Original protocol with 2‑byte payload length (≤ 64 KiB files)
- **v2**: Extended protocol with 4-byte payload length (≤ 4 GiB files) - use for all file transfers
- File transfer packets use v2 format by default for optimal compatibility

**Interactive Features:**
- **Waveform Seeking**: Tap anywhere on audio waveforms to jump to that playback position
- **Large File Support**: v2 protocol enables multi-GiB file transfers through fragmentation
- **Unified Experience**: Identical UX between platforms with enhanced user control

The guide is organized into:

- Protocol overview (BitchatPacket + File Transfer payload)
- Fragmentation, progress reporting, and cancellation
- Receive path, validation, and persistence
- Sender path (audio + images)
- Interactive features (audio waveform seeking)
- UI/UX behavior (recording, sending, playback, image rendering)
- File inventory (source files and their roles)


---

## 1) Protocol Overview

Bitchat BLE transport carries application messages inside the common `BitchatPacket` envelope. File transfer reuses the same envelope as public and private messages, with a distinct `type` and a TLV‑encoded payload.

### 1.1 BitchatPacket envelope

Fields (subset relevant to file transfer):

- `version: UByte` — protocol version (`1` for v1, `2` for v2 with extended payload length).
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

### 1.2 Binary Protocol Extensions (v2)

#### v2 Header Format Changes

**v1 Format (original):**
```
Header (13 bytes):
Version: 1 byte
Type: 1 byte
TTL: 1 byte
Timestamp: 8 bytes
Flags: 1 byte
PayloadLength: 2 bytes (big-endian, max 64 KiB)
```

**v2 Format (extended):**
```
Header (15 bytes):
Version: 1 byte (set to 2 for v2 packets)
Type: 1 byte
TTL: 1 byte
Timestamp: 8 bytes
Flags: 1 byte
PayloadLength: 4 bytes (big-endian, max ~4 GiB)
```

- **Header Size**: Increased from 13 to 15 bytes.
- **Payload Length Field**: Extended from 16 bits (2 bytes) to 32 bits (4 bytes), allowing file transfers up to ~4 GiB.
- **Backward Compatibility**: Clients must support both v1 and v2 decoding. File transfer packets always use v2.
- **Implementation**: See `BinaryProtocol.kt` with `getHeaderSize(version)` logic.

#### Use Cases for v2
- **Large Audio Files**: Professional recordings, podcasts, or music samples.
- **High-Resolution Images**: Full-resolution photos from modern smartphones.
- **Future File Types**: PDFs, documents, archives, or other large media.

#### Interoperability Requirements
- Clients receiving v2 packets must decode 4-byte `PayloadLength` fields.
- Clients sending file transfers should preferentially use v2 format.
- Fragmentation still applies: large files are split into fragments that fit within BLE MTU constraints (~128 KiB per fragment).

### 1.3 File Transfer TLV payload (BitchatFilePacket)

The file payload is a TLV structure with mixed length field sizes to support large contents efficiently.

- Defined in `app/src/main/java/com/bitchat/android/model/BitchatFilePacket.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/model/BitchatFilePacket.kt)

Canonical TLVs (v2 spec):

- `0x01 FILE_NAME` — UTF‑8 bytes
  - Encoding: `type(1) + len(2) + value`
- `0x02 FILE_SIZE` — 4 bytes (UInt32, big‑endian)
  - Encoding: `type(1) + len(2=4) + value(4)`
  - Note: v1 used 8 bytes (UInt64). v2 standardizes to 4 bytes. See Legacy Compatibility below.
- `0x03 MIME_TYPE` — UTF‑8 bytes (e.g., `image/jpeg`, `audio/mp4`, `application/pdf`)
  - Encoding: `type(1) + len(2) + value`
- `0x04 CONTENT` — raw file bytes
  - Encoding: `type(1) + len(4) + value(len)`
  - Exactly one CONTENT TLV per file payload in v2 (no TLV‑level chunking); overall packet fragmentation happens at the transport layer.

Encoding rules:

- Standard TLVs use `1 byte type + 2 bytes big‑endian length + value`.
- CONTENT uses a 4‑byte big‑endian length to allow payloads well beyond 64 KiB.
- With the v2 envelope (4‑byte payload length), CONTENT can be large; transport still fragments oversize packets to fit BLE MTU.
- Implementations should validate TLV boundaries; decoding should fail fast on malformed structures.

Decoding rules (v2):

- Accept the canonical TLVs above. Unknown TLVs should be ignored or cause failure per implementation policy (current Android rejects unknown types).
- FILE_SIZE expects `len=4` and is parsed as UInt32; receivers may upcast to 64‑bit internally.
- CONTENT expects a 4‑byte length field and a single occurrence; if multiple CONTENT TLVs are present, concatenate in order (defensive tolerance).
- If FILE_SIZE is missing, receivers may fall back to `content.size`.
- If MIME_TYPE is missing, default to `application/octet-stream`.

Legacy Compatibility (optional, for mixed‑version meshes):

- FILE_SIZE (0x02): Some legacy senders used 8‑byte UInt64. Decoders MAY accept `len=8` and clamp to 32‑bit if needed.
- CONTENT (0x04): Legacy payloads might have used a 2‑byte TLV length with multiple CONTENT chunks. Decoders MAY support concatenating multiple CONTENT TLVs with 2‑byte lengths if encountered.


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
    - Other files: `files/files/incoming/`
  - Filename strategy:
    - Prefer the transmitted `fileName` when present; sanitize path separators.
    - Ensure uniqueness by appending `" (n)"` before the extension when a name exists already.
    - If `fileName` is absent, derive from MIME with a sensible default extension.
  - MIME determines extension hints (`.m4a`, `.mp3`, `.wav`, `.ogg` for audio; `.jpg`, `.png`, `.webp` for images; otherwise based on MIME or `.bin`).
- A synthetic chat message is created with content markers pointing to the local path:
  - Audio: `"[voice] /abs/path/to/file"`
  - Image: `"[image] /abs/path/to/file"`
  - Other: `"[file] /abs/path/to/file"`
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

## 5.6 Interactive Audio Features

### 5.6.1 Waveform Seeking

- Audio waveforms in chat messages are fully interactive: users can tap anywhere on the waveform to jump to that position in the audio playback.
- On tap, the seek position is calculated as a fraction of the waveform width (0.0 = beginning, 1.0 = end).
- This works for both playing and paused audio states.
- The MediaPlayer is seeked to the calculated position immediately, with visual feedback via progress bar update.
- Tapping provides precise control - e.g., tap 25% through waveform jumps to 25% through audio.
- No haptic feedback or visual indicator; the progress bar update serves as immediate feedback.

Waveform Canvas Implementation:
- `WaveformCanvas` uses `pointerInput` with `detectTapGestures` to capture tap events.
- Tap position is converted to a fraction: `position.x / size.width.toFloat()`.
- Clamped to 0.0-1.0 range for safety.
- `onSeek` callback is invoked with the calculated position fraction.
- Only enabled when `onSeek` is provided (disabled for sending in progress).

VoiceNotePlayer Seeking:
- Accepts position fraction (0.0-1.0) and converts to milliseconds: `seekMs = (position * durationMs).toInt()`.
- Calls `MediaPlayer.seekTo(seekMs)` to jump to the exact position.
- Updates progress state immediately for UI responsiveness even before playback reaches the new position.

Files:
- `app/src/main/java/com/bitchat/android/ui/MessageComponents.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/MessageComponents.kt) — VoiceNotePlayer with seekTo function
- `app/src/main/java/com/bitchat/android/ui/media/WaveformViews.kt` (/Users/cc/git/bitchat-android/app/src/main/java/com/bitchat/android/ui/media/WaveformViews.kt) — Interactive WaveformCanvas with tap handling

---

## 6) Edge Cases and Notes

- Filename collisions on receiver: prefer the sender‑supplied name if present; always uniquify with a ` (n)` suffix before the extension to prevent overwrites.
- Path markers in messages
  - We use simple content markers: `"[voice] <abs path>", "[image] <abs path>", "[file] <abs path>"` for local rendering. These are not sent on the wire; the actual file bytes are inside the TLV payload.
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

1. **Implement v2 protocol support**: Support both v1 (2-byte payload length) and v2 (4-byte payload length) packet decoding. Use v2 format for file transfer packets to enable large file transfers.
2. Implement `BitchatFilePacket` TLV exactly as specified:
   - FILE_NAME and MIME_TYPE: `type(1) + len(2) + value`
   - FILE_SIZE: `type(1) + len(2=4) + value(4, UInt32 BE)`
   - CONTENT: `type(1) + len(4) + value`
3. Embed the TLV into a `BitchatPacket` envelope with `type = FILE_TRANSFER (0x22)` and the correct `recipientID` (broadcast vs private).
4. Fragment, send, and report progress using a transfer ID derived from `sha256(payload)` so the UI can map progress to a message.
5. Support cancellation at the fragment sender: stop sending remaining fragments and propagate a cancel to the UI (we remove the message).
6. On receive, decode TLV, persist to an app directory (separate audio/images/other), and create a chat message with content marker `"[voice] path"`, `"[image] path"`, or `"[file] path"` for local rendering.
7. Audio sender and receiver should use the same waveform extractor so visuals match; a 120‑bin histogram is a good balance.
8. **Implement interactive waveform seeking**: Tap waveforms to jump to that audio position. Calculate tap position as fraction (0.0-1.0) of waveform width.
9. For images, optionally downscale to keep TLV small; JPEG 85% at 512 px longest edge is a good baseline.
10. Mirror the UX:
    - Recording overlay that does not collapse the IME; hide the caret while recording; add 500 ms end padding.
    - Voice: waveform fill for send/playback; cancel overlay; **tap-to-seek support**.
    - Images: dense block‑reveal with no gaps during sending; cancel overlay; fullscreen viewer with save.
    - Generic files: render as a file pill with icon + filename; support open/save via the host OS.

Following the above should produce an interoperable and matching experience across platforms.
