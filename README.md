# J2ME-Bloat

J2ME-Bloat is a Java ME (J2ME) emulator for Android. It runs classic Java mobile games and apps on modern phones and tablets. It is based on [J2ME Loader](https://github.com/nikita36078/J2ME-Loader).

<p>
  <img src="screenshots/screen.jpg" width="200" alt="Screenshot 1">
  <img src="screenshots/screen2.jpg" width="200" alt="Screenshot 2">
  <img src="screenshots/screen3.jpg" width="200" alt="Screenshot 3">
</p>

## Features

- Runs most 2D and 3D J2ME games, including Mascot Capsule 3D titles
- Imports games from JAR/JAD files, a whole folder or a **ZIP archive**
- Batch imports with a progress bar, a cancel button and a copyable list of failed files
- Material 3 design on every screen (library, game settings, key mapping, dialogs), with light and dark themes
- Favorites that stay at the top of the library, and multi-select to delete or favorite many games at once (long-press a game)
- Draggable scroll bar with a letter indicator for long lists
- Customizable virtual keyboard and touch controls with a modern dark look
- Separate screen, scaling and control settings for every game

## Install

1. Open [Releases](https://github.com/mesflit/J2ME-Bloat/releases/latest) and download the `.apk` file.
2. Open the file to install it. If Android asks, allow installing apps from your browser or file manager.
3. To update, install the newer APK over the old one. Every release is signed with the same key, so your games and settings stay.

> **Coming from J2ME Loader or an older J2ME-Bloat build?** The package name is now `com.mesflit.j2mebloat`, so older builds show up as a separate app. Use **Settings → Import/export data** in the old app to export your games and settings, import them in the new app, then uninstall the old one.

## Adding games

J2ME-Bloat does not include any games. You add game files that you own. Tap **Add games** in the bottom-right corner and pick one of these:

| Option | What it does |
|---|---|
| **JAR / JAD files** | Install one or more game files. Select the `.jad` together with its `.jar` and they are matched automatically. `.kjx` files work too. |
| **ZIP archive** | Install every `.jar`, `.jad` and `.kjx` inside a `.zip`, including subfolders and ZIPs inside the ZIP. |
| **Folder** | Scan a folder and all its subfolders. ZIP archives in the folder are opened as well. |

You can also tap a `.zip` or `.jar` file in your file manager and choose **Open with J2ME-Bloat**.

While importing:

- The progress bar shows how many games are done, and the screen stays on.
- **Cancel** stops after the current game. Games installed so far stay in your library.
- **Run in background** hides the window. The import continues with a progress notification, even if you leave the app; tap the notification to open it again. On Android 13 and newer the app asks for permission to show notifications.
- Games that are already in your library are skipped; a newer version updates the installed game.
- At the end you see how many games were installed and which ones failed. **Copy failed list** puts the failed file names on the clipboard.
- Files from a ZIP are extracted temporarily and deleted afterwards. For a big archive, keep at least as much free space as the archive size.

## Getting games and making a ZIP

### Where your games can come from

- **Your old phone.** Games you bought years ago are often still on your old Nokia, Sony Ericsson, Samsung or Motorola phone or its memory card. Connect the phone (or the card, with a card reader) to a computer and look for `.jar` and `.jad` files, usually in folders such as `Games`, `Java`, `Applications` or `Other`. On some phones you can also send a game over Bluetooth to your Android device. Old PC sync tools such as Nokia PC Suite can back up installed apps too.
- **Old backups.** Search old computer folders, CDs and memory cards from previous phones for `.jar`, `.jad` and `.zip` files.
- **Free and open-source games.** Many developers publish J2ME games and homebrew with a license that allows sharing. A good place to start is the [j2me topic on GitHub](https://github.com/topics/j2me). Check each project's license before you redistribute it.
- **Your own games.** If you develop J2ME apps, install your own builds directly.

Only use games that you own or that are licensed for free distribution. Many sites host commercial J2ME games without permission from their publishers; those games are still under copyright, so please don't download them from such sites or share them.

### Packing games into a ZIP

Putting your games in one ZIP makes it easy to move them to a new phone and import them in one step.

- **Android:** in your file manager (for example Files by Google or your phone's built-in one), select the game files or the folder that holds them, then choose **Compress** or **Create ZIP**.
- **Windows:** select the files, right-click and choose **Send to → Compressed (zipped) folder** (on Windows 11: **Compress to ZIP file**).
- **macOS:** select the files, right-click and choose **Compress**.
- **Linux:** run `zip -r my-games.zip my-games/`.

Tips:

- Keep each game's `.jad` and `.jar` together with the same base name (for example `Game.jad` and `Game.jar`).
- Subfolders are fine, for example one folder per game or per screen size.
- Only ZIP is supported. Convert RAR or 7z archives to ZIP first.

## Playing

- Tap a game to start it.
- Long-press a game for **Settings** (resolution, scaling, controls, keyboard layout), **Rename**, **Create shortcut**, **Reinstall** and **Delete**.
- Use the toolbar to search, switch between list and grid view, and change the sort order.

### Troubleshooting

| Problem | Fix |
|---|---|
| The game is slow | Turn off filtering in the game's settings. |
| The image flickers | Turn on "Immediate processing mode" in the game's settings. |
| The screen is too small or too big | Set the screen resolution in the game's settings to the game's real resolution, for example 240×320. |
| "App not installed" when updating | The installed build was signed with a different key. Uninstall it once and install the new release; later updates install over it. |
| No games found in a ZIP | Make sure the archive contains `.jar`, `.jad` or `.kjx` files. RAR and 7z are not supported. |

## Building

Requirements: Android SDK, Android NDK and JDK 21.

```sh
./gradlew :app:assembleOpenRelease
```

The APK is written to `app/build/outputs/apk/open/release/`.

For a signed local build, create `keystore.properties` in the project root (it is ignored by git):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without it, the APK is signed with your local debug key.

## Automatic releases

Every push to `master` builds a signed APK with `.github/workflows/release.yml` and publishes it as a new release named `J2ME-Bloat 2.0.<build number>`. The build number is the commit count, so each release can be installed over the previous one.

Android only installs an update when it is signed with the **same key** as the installed version. The workflow therefore needs a fixed signing key stored in the repository secrets. If the key is missing, or the APK ends up signed with a debug key, the build fails instead of publishing an APK that would not install as an update.

One-time setup:

1. Create a signing key with `keytool` (included with the JDK). **Keep the file and the password safe.** If you lose them, users cannot install the next update over their current version.

   ```sh
   keytool -genkeypair -v -keystore release.jks -alias j2mebloat \
     -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
   ```

2. Encode the key as base64:

   ```sh
   base64 -w0 release.jks > release.jks.b64      # Linux
   base64 -i release.jks -o release.jks.b64      # macOS
   ```

3. In the repository, open **Settings → Secrets and variables → Actions → New repository secret** and add:

   | Secret | Value |
   |---|---|
   | `SIGNING_KEY` | Contents of `release.jks.b64` |
   | `KEY_STORE_PASSWORD` | Keystore password |
   | `KEY_ALIAS` | `j2mebloat` (or the alias you chose) |
   | `KEY_PASSWORD` | Key password (same as the keystore password for PKCS12; may be left empty) |

4. Push to `master`, or open **Actions → Release → Run workflow**. The run summary shows the APK's SHA-256 signing fingerprint, which should be the same for every release.

## Legal

J2ME-Bloat does not include or distribute any games. Games belong to their developers and publishers. Only add games that you own or that are licensed for free distribution.

## License and credits

The source code is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)). J2ME-Bloat is based on [J2ME Loader](https://github.com/nikita36078/J2ME-Loader) by Nikita Shakarun and contributors.
