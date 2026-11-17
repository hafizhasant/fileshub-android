# AGENTS.md — FilesHub project rules

FilesHub is a FOSS Android file manager in Kotlin and Jetpack Compose. APKs are built by
GitHub Actions (`.github/workflows/build.yml`), never locally. Do not install a JDK,
Android SDK or Gradle on the phone.

## Project identity

- Package is `org.fileshub.app`. Keep it unchanged.
- Keep `app/fileshub-debug.keystore` unchanged. Updates must install over the existing app.

## Network policy

- Network code is allowed only in `app/src/main/java/org/fileshub/app/Net.kt` and
  `Transfer.kt`.
- No trackers, analytics, ads or hard-coded hosts.

## Before every commit

- `scripts/audit.sh` must pass before every commit. Run it first.

## UI conventions

- Use Material 3 and the existing helpers: `TopRow`, `glass`, `FileRow`, `fmt`, `uniq`,
  `open`, `openWith`, `share`, `Clip`, and the `S` settings object.

## Dependencies

- Do not add a library unless it is FOSS. Say which library and its license first.

## Releases

- Each release bumps `versionCode` and `versionName` in `app/build.gradle.kts`, and the
  version label in `Extras.kt`.

## Finish the work

- Finish with `git add -A && git commit && git push` and then `gh run watch`.
- If the build fails, read the errors with `gh run view --log-failed | grep "^e:"`, fix
  them, and push again.

## Replies

- Keep replies short. Show each command and its expected output.
