# 📖 NightLibrary

**A privacy-first, offline Android vault for personal media — disguised as a reading companion.**

NightLibrary lets you securely store photos, videos, documents, and sensitive information locally on your device, with zero cloud dependency and zero tracking. It's built to look and feel like an ordinary reading app on the surface, while functioning as a fully encrypted personal vault underneath.

---

## 🔐 Security & Encryption

Security is the core of NightLibrary, not an afterthought. All sensitive data — both metadata and file contents — is encrypted at rest using AES-256.

- **Encrypted database (SQLCipher):** All app metadata (vault entries, file references, tags, notes, sensitive text) is stored in a SQLCipher-encrypted SQLite database layered under Room, so the entire database file is unreadable without the derived key — not just individual fields.
- **Chunk-based file encryption:** Media files (photos, videos, documents) are encrypted using AES-256 in fixed-size chunks rather than as a single monolithic blob. This allows:
  - Large media files to be encrypted/decrypted incrementally instead of loading the full file into memory
  - Faster partial reads (e.g., generating thumbnails or previews without decrypting an entire large video)
  - Lower memory footprint on lower-end Android devices
- **Zero cloud, zero analytics:** No file, thumbnail, or metadata ever leaves the device. No third-party analytics or crash-reporting SDKs are bundled that could leak usage patterns.
- **Disguised UX layer:** The app presents as a night-reading companion by default, keeping the existence of the vault non-obvious at a glance.

> **Note:** Chunk size, key derivation approach, and other cryptographic implementation details are documented inline in the codebase (see `/security` or equivalent module) for anyone auditing the implementation.

---

## ✨ Features

- 🔐 Secure Media Vault
- 📥 Link-Based Media Download
- 🖼️ Import Photos & Videos from Gallery
- 📁 Import Files from Device Storage
- 🔑 Password Locker
- 📇 Private Contacts Storage
- ⚡ Floating Quick Launcher (Quick Save)
- 📋 Clipboard-Based Media Save
- 🔕 Silent Download Mode
- 🌐 Incognito Browsing Mode
- 🔒 PIN Authentication
- 🧬 Biometric Unlock Support
- 🚨 Emergency Lock
- 🕶️ Discreet Access Interface
- 🗂️ Hidden Storage (.nomedia Vault)
- 📴 Offline Access to Saved Content

---

## 🏗️ Architecture

Built on a clean, testable Android architecture:

- **Pattern:** MVVM + Repository
- **Dependency Injection:** Hilt
- **Concurrency:** Kotlin Coroutines for structured background work
- **Persistence:** Room (on top of SQLCipher-encrypted SQLite)
- **Background Work:** WorkManager for durable, retryable operations
- **Storage:** Scoped Storage API (Android 11+ compliant)

```
              UI Layer (Compose/Views)
                        │
                        ▼
              ViewModel (state holder)
                        │
                        ▼
           Repository (single source of truth)
                        │
           ┌────────────┴────────────┐
           ▼                         ▼
    Room + SQLCipher         Chunk-Based File Encryption
   (encrypted metadata)          (encrypted media)
```

---

## 🧰 Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| Architecture | MVVM + Repository |
| DI | Hilt |
| Concurrency | Coroutines |
| Local DB | Room + SQLCipher |
| File Encryption | AES-256, chunk-based |
| Background Work | WorkManager |
| Storage | Android Scoped Storage API |

---

## 📱 Why NightLibrary

Most "vault" or "hide photos" apps on the Play Store either rely on basic obfuscation (renaming file extensions, hiding via `.nomedia`) or encrypt files as single large blobs, which is slow and memory-heavy on real devices. NightLibrary was built to solve both problems properly:

1. **Real encryption, not obfuscation** — SQLCipher for metadata + AES-256 chunk-based encryption for files means there's no unencrypted copy of your data sitting on disk.
2. **Performance on real hardware** — Chunked encryption keeps memory usage low and previews fast, even for large videos, instead of decrypting entire files just to show a thumbnail.

---

## 🚀 Getting Started

```bash
git clone https://github.com/ShivamKumarPTU/NightLibrary.git
```

Open in Android Studio, sync Gradle, and run on a device/emulator running Android 11 (API 30) or higher.

---

## 📄 License

[Add your license here]

---

## 👤 Author

**Shivam Kumar**
Android Developer · Kotlin, MVVM, Jetpack
[GitHub](https://github.com/ShivamKumarPTU) · [Portfolio](https://shivam-app-studio.vercel.app/)
