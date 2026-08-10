---
name: android-security
description: Android security standards, end-to-end encryption protocols, key storage (Android Keystore), Noise protocol, and zero-trust mesh privacy.
---

# Android Security Skill

## Security Principles
- Fail-closed security architecture: invalid signatures or unauthenticated packets must be rejected immediately.
- Encryption protocols (Noise protocol framework, AES-GCM / ChaCha20-Poly1305) must remain uncompromised.
- Use Android Keystore for sensitive cryptographic key storage.
- Zero-trust privacy: do not transmit raw device identifiers, unencrypted user payload data, or location coordinates.
