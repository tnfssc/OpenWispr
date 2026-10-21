---
name: Provider API keys are encrypted with Android Keystore directly
description: Why EncryptedSharedPreferences was dropped and how old keys are migrated
---
androidx.security `EncryptedSharedPreferences` is deprecated (Google points to Keystore directly) and
was the only source of compiler warnings in our own code. `SecretBox` (`:openwispr`) now seals keys with
an AES-GCM key held in Android Keystore, stored in `openwispr_provider_keys` shared prefs.

Keys saved before 0.1.15 sit in the old `openwispr_provider_secrets` file; `OpenWisprConfigStore`
moves them over on first read and deletes the old file, so updating does not sign the user out.
Verified on the API 36 emulator: key saved in v0.1.14 survived an upgrade and later restarts. The
migration (and the security-crypto dependency) can go once no one runs 0.1.14 or older.
