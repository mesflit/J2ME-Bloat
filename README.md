J2ME-Bloat

![Downloads](https://img.shields.io/github/downloads/mesflit/J2ME-Bloat/continuous/total?style=for-the-badge&label=Continuous%20Downloads&color=blue)

J2ME-Bloat is an Android Java ME (J2ME) emulator based on "J2ME Loader" (https://github.com/nikita36078/J2ME-Loader).

It allows you to run classic Java mobile games and applications on modern Android devices.

Features

- Java ME (J2ME) application emulation.
- Support for 2D and 3D games.
- Built-in collection of thousands of J2ME games.
- Automatic game import on first launch.
- Customizable virtual keyboard and touchscreen controls.
- Individual settings for each game.
- Display scaling and screen configuration.
- Mascot Capsule 3D support.

Game Collection

J2ME-Bloat includes a large collection of bundled Java games.

Browse the complete game list:

"View All Games [GAMES.md](GAMES.md)"

The game list is automatically generated from the bundled JAR files. Different versions, resolutions and regional releases may appear separately.

Screenshots

<!-- Add screenshots here -->Building

Requirements

- Android SDK
- Android NDK
- JDK
- Gradle

Build Debug APK

./gradlew :app:assembleOpenDebug

Build Release APK

./gradlew :app:assembleOpenRelease

Build outputs are located in:

app/build/outputs/apk/open/

Based On

J2ME-Bloat is based on "J2ME Loader" (https://github.com/nikita36078/J2ME-Loader).

License

The application source code retains the Apache License 2.0 licensing terms.

Bundled game files are copyrighted by their respective developers and publishers. Their inclusion does not imply ownership or permission to redistribute them. Please respect the rights and licenses of the original copyright holders.

---

J2ME-Bloat — Bringing classic Java mobile games back to Android.
