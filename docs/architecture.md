# Architecture

Ginkgo is a single Android app module. Packages separate launcher navigation, Android views, persistence, and the two bookshelf integrations.

| Package | Responsibility |
| --- | --- |
| `launcher` | Installed app discovery, app intents, and page identifiers. |
| `settings` | Launcher preferences, pinned apps, and local book history. |
| `shelf` | Source-neutral shelf entries, sorting, and recent-book selection. |
| `shelf.local` | Read-only access to `Books/` and Duokan book-ID deep links. |
| `shelf.weread` | WeRead deep links, account state, read-only sync, cached shelf data, and open history. |
| `shelf.cover` and `shelf.cache` | Local cover extraction and shared thumbnail storage. |
| `wallpaper` | Cover collage composition and export. |
| `update` | Manually check the latest GitHub release, verify the downloaded APK, and hand it to Android’s installer. |
| `ui` and `ui.page` | Reusable e-ink views and standalone page rendering. |

`InkActivity` coordinates navigation, lifecycle, permissions, and the page callbacks. Keep its fully qualified component name stable: Android uses it to persist the default launcher selection. Views should not change account state or write the book library directly.

Validate changes with `./gradlew :app:assembleRelease :app:lintRelease`. A release tag must match the app version and the corresponding file in `.github/release-notes/`. The release job publishes `update.json` with the signed APK. The updater uses its `versionCode` to compare builds and verifies the download’s size, SHA-256, package, version, and signer before exposing it to the system installer through a read-only URI.
