<p align="center"><img src="logo.svg" width="96" alt="Cairn logo"></p>

# Cairn

A Material 3 file manager for Android, built with Kotlin and Jetpack Compose. No ads, no trackers, and **no internet permission**.

- Storage overview and quick categories (images, videos, audio, documents, archives, downloads, APKs, screenshots)
- Browse folders, create, rename, multi-select, open files
- Recycle bin with restore
- Dark and light themes

Needs Android 11 or newer and "All files access".

## Build

Open in Android Studio, or run `gradle assembleDebug` with JDK 17. CI builds an APK on every push, and pushing a `v*` tag publishes a release.

CI also checks that the manifest has no network permission and the sources have no network code (`scripts/audit.sh`).

## License

MIT. See [LICENSE](LICENSE).
