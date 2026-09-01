# 📡 CampusMesh — Decentralized Offline BLE Mesh Messenger for Android

**CampusMesh** is a peer-to-peer, decentralized offline messaging application for Android. It enables encrypted text messaging, multi-hop mesh routing, and profile sharing directly between nearby mobile phones — **without internet connection, cellular data, Wi-Fi routers, or central servers.**

---

## ✨ Features

- 🔐 **End-to-End Encryption (E2EE)**: Hybrid RSA-2048 key exchange with per-message AES-256-GCM authenticated encryption.
- ⚡ **Bidirectional BLE GATT Mesh**: Automatic client/server GATT connection establishing real-time two-way messaging between phones in range.
- 🔄 **Store-and-Forward Routing**: Multi-hop packet relaying with destination-aware routing, hop counting, TTL limits, and duplicate packet suppression.
- 👤 **Smart Profile & Avatar Sync**: Displays real names and custom profile pictures. Avatar thumbnails are exchanged over BLE using SHA-256 hash checks so images only transfer when changed.
- 🔒 **Private Media Storage**: Profile images are stored in private internal app storage (`filesDir/profiles/`) with `.nomedia` protection, hiding them from the device's public photo gallery.
- 📬 **Read Receipts & Unread Badges**: Real-time delivery tracking (`PENDING` 🕐 → `SENT` ✓ → `DELIVERED` ✓✓ → `SEEN` ✓✓ in bright blue) via automatic BLE read receipt packets.
- 🔔 **Interactive System Notifications**: High-priority Android notifications for incoming messages with avatar previews and direct deep-link intent into chat.
- 🔋 **Persistent Background Mesh Service**: Foreground service keeping BLE advertising, scanning, and GATT routing active even when the screen is locked or the app is backgrounded.
- 📊 **Diagnostic Demonstration Mode**: Real-time debug dashboard showing active BLE peers, connected GATT addresses, RSSI signal strength, packets routed, duplicates blocked, and encryption statuses.

---

## 📱 Tech Stack & Architecture

- **Language**: Kotlin 2.1
- **UI Framework**: Jetpack Compose with Material 3 & Custom Dark Theme
- **Architecture**: MVVM + Clean Architecture with Hilt Dependency Injection
- **Database**: Room DB v8 (with SQL migrations)
- **Transport**: Bluetooth Low Energy (BLE) Advertising, Scanning, and GATT Client/Server
- **Cryptography**: RSA-2048 & AES-256-GCM (`android.util.Base64` & Java Security Specs)

---

## 🚀 How to Build & Export APK

### Prerequisites
- Android Studio Ladybug (or newer)
- JDK 17 / 21
- Android SDK (Compile SDK 36, Min SDK 26)

### Command Line Build
```bash
# Debug APK
.\gradlew.bat assembleDebug

# Release APK (Optimized & ProGuard Shrunk)
.\gradlew.bat assembleRelease
```
The output APK files are generated at:
`app/build/outputs/apk/debug/app-debug.apk`
`app/build/outputs/apk/release/app-release-unsigned.apk`

---

## 🛠️ Step-by-Step GitHub Setup Guide

### 1️⃣ Initialize Git Repository (if not done yet)
Open your terminal in the project directory:
```bash
git init
git add .
git commit -m "Initial commit: CampusMesh complete offline BLE mesh messenger"
```

### 2️⃣ Create a Repository on GitHub
1. Go to [GitHub.com](https://github.com) and click **New Repository**.
2. Name it `CampusMesh` (or your preferred name).
3. Do **NOT** check "Initialize this repository with a README" (since we already have one).
4. Click **Create repository**.

### 3️⃣ Push Code to GitHub
Copy the commands shown on GitHub:
```bash
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/CampusMesh.git
git push -u origin main
```

---

## 📦 How to Attach APK to GitHub Releases

To let users download and test your APK directly from your GitHub page:

1. Go to your GitHub repository page (`github.com/YOUR_USERNAME/CampusMesh`).
2. On the right sidebar, click **Create a new release** (or **Releases** > **Draft a new release**).
3. Type a tag version (e.g. `v1.0.0`) and title (e.g., `CampusMesh v1.0.0 Release`).
4. Drag and drop your built APK (`app-debug.apk` or `app-release.apk`) into the **Attach binaries by dropping them here or selecting them** box.
5. Click **Publish release**.

---

## 📜 License
Educational / Open Source Project. Developed for university research and offline communication testing.
