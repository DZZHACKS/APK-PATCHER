<div align="center">

# DMZ APK Patcher

**Standalone Android app that patches installed games with mod menus — no PC, no root required.**

![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![Arch](https://img.shields.io/badge/arch-arm64--v8a-blue)
![Lang](https://img.shields.io/badge/UI-EN%20%7C%20FR%20%7C%20ES%20%7C%20RU%20%7C%20PT--BR-purple)
![License](https://img.shields.io/badge/license-proprietary-red)

</div>

---

## Features

- **Game auto-detection** by package name (`games.json` registry)
- Supports **APK** standalone, **APKS** (Play Store splits), **XAPK** (+ OBB restoration)
- **Menu auto-picked** from the bundled library — no more "paste the right .so"
- **In-app keystore creator** — PKCS12 RSA 2048, self-signed 25 years
- **v1 + v2** signing (apksig)
- Automatic **uninstall → install** flow to bypass signature mismatch
- **arm64-v8a only** output — arm32/x86 splits & libs stripped automatically
- **Material 3 dark** UI · 5 languages (EN default · FR · ES · RU · PT-BR)
- Bottom nav: **Home** · **Logs** · **Settings** · **About**

---

## Supported games

| Game | Package | Variants |
|---|---|---|
| **Last Day on Earth: Survival** | `zombie.survival.craft.z` | BlackMods · Zuke BR |
| **Days After** | `games.alternativa.projectv.test` | D-M-Z |
| **Grim Soul** | `fantasy.survival.game.rpg` | D-M-Z |

To add a new game: drop an entry in [`app/src/main/assets/games.json`](app/src/main/assets/games.json), place the menu file in `assets/menus/`, rebuild.

---

## How it works

```
┌────────────────────────┐
│ 1. Pick target         │  installed app (auto splits) · APK · APKS · XAPK
├────────────────────────┤
│ 2. Detect game         │  package match → games.json entry
├────────────────────────┤
│ 3. Pick variant        │  silent if only one, selector if multiple (LDOE)
├────────────────────────┤
│ 4. Patch (dexlib2)     │  • scan classes*.dex (multi-dex, Unity-safe)
│                        │  • merge menu classes into classes.dex
│                        │  • inject bootstrap hook into launcher onCreate
│                        │  • strip lib/armeabi-v7a, lib/x86, lib/x86_64
├────────────────────────┤
│ 5. Sign (apksig)       │  v1 + v2 with your keystore
├────────────────────────┤
│ 6. Install             │  PackageInstaller · uninstall previous if needed
│                        │  OBB restored to Android/obb/<pkg>/ for XAPK
└────────────────────────┘
```

Two injection modes:

- **Load-library** (`.so` only) — generates a synthetic `com.android.support.Launcher` that calls `System.loadLibrary(stem)` for each lib, invoked from the launcher activity's `onCreate`.
- **Bootstrap-call** (full menu APK) — merges every non-Android class of the menu into `classes.dex`, then injects `invoke-static {}` or `invoke-static/range {thisReg}, Lmenu/MainActivity;->Start(...)V` at the top of the game's launcher `onCreate`.

---

## Build

```bash
# Requirements: JDK 17, Android SDK (compileSdk 34), gradle wrapper bundled

# Keystore env vars (omit for a debug build):
export ZLI_KEYSTORE=/path/to/release.jks
export ZLI_KEYSTORE_PASS=...
export ZLI_KEY_ALIAS=zli       # optional, default "zli"
export ZLI_KEY_PASS=...        # optional, falls back to ZLI_KEYSTORE_PASS

./gradlew :app:assembleRelease
```

Output → `app/build/outputs/apk/release/app-release.apk`

Never commit `keystore.properties`, `.jks` files, or any plaintext secret. Use env vars or `EncryptedSharedPreferences`.

---

## Permissions

| Permission | Purpose |
|---|---|
| `QUERY_ALL_PACKAGES` | list installed games |
| `REQUEST_INSTALL_PACKAGES` | install the patched APK |
| `REQUEST_DELETE_PACKAGES` | uninstall the previous version (signature mismatch) |
| `MANAGE_EXTERNAL_STORAGE` | copy OBB into `Android/obb/<pkg>/` |
| `WRITE_EXTERNAL_STORAGE` | legacy (maxSdk=29) |

---

## Project layout

```
APK-Patcher/
├── app/                              # Android app (com.dmz.apkpatcher)
│   └── src/main/
│       ├── assets/
│       │   ├── games.json            # game registry
│       │   └── menus/                # bundled menu APKs + .so
│       ├── java/com/dmz/apkpatcher/
│       │   ├── MainActivity.java     # wizard controller (7 screens)
│       │   ├── GameRegistry.java     # games.json parser
│       │   ├── ApkManifest.java      # package/launcher extraction
│       │   └── InstalledAppsAdapter.java
│       └── res/                      # Material 3 dark, 5 locales
└── engine/                           # patching library (pure Java)
    └── src/main/java/dmz/engine/
        ├── ApkPatcher.java           # dex merge + repack + ABI strip
        ├── SyntheticMenu.java        # .so → synthetic menu APK
        ├── KeystoreCreator.java      # PKCS12 RSA 2048 BouncyCastle
        └── Signer.java               # apksig v1+v2 wrapper
```

---

## Stack

- **Smali/Dex**: [smali-tools](https://github.com/google/smali) 3.0.3
- **Signing**: [apksig](https://android.googlesource.com/platform/tools/apksig/) 8.7.3
- **Crypto**: [BouncyCastle](https://www.bouncycastle.org/) 1.78.1 (`bcprov-jdk18on` + `bcpkix-jdk18on`)
- **UI**: AndroidX AppCompat + Material Components (M3)
- **Min SDK**: 24 (Android 7.0) · **Target SDK**: 34

---

## Credits

**Developed by [DZZHACKS](https://github.com/DZZHACKS)**

- Design, engine, UI, integration — **DZZHACKS**
- Bundled menus:
  - **LDOE BlackMods** — BlackMods
  - **LDOE Zuke BR** — Zuke
  - **Days After / Grim Soul D-M-Z** — DZZHACKS

Thanks to every contributor of the open-source tools used (smali, apksig, BouncyCastle).

---

## Disclaimer

This tool is provided for educational purposes and private use on APKs you own. Using it on online games may violate their Terms of Service and result in an account ban. The authors are not responsible for how the tool is used.

---

<div align="center">

**© DZZHACKS** · Not affiliated with Kefir, Alternativa or any game publisher.

</div>
