<p align="center"><img src="logo.svg" width="96" alt="Cairn logo"></p>

# Cairn

A Material 3 file manager for Android, built with Kotlin and Jetpack Compose. No ads, no trackers, no analytics. The network is used only for FTP and SMB servers you add yourself.

- Storage overview and quick categories (images, videos, audio, documents, archives, downloads, APKs, screenshots)
- Browse folders, create, rename, multi-select, open files
- Long-press actions (open with, share, cut, copy, paste, rename, delete), search, recycle bin with restore
- FTP and SMB browsing
- Dark and light themes

Needs Android 11 or newer and "All files access".

## Build

Open in Android Studio, or run `gradle assembleDebug` with JDK 17. CI builds an APK on every push, and pushing a `v*` tag publishes a release.

CI also checks that network code lives only in `Net.kt` and that no trackers are bundled (`scripts/audit.sh`).

## License

MIT. See [LICENSE](LICENSE).
