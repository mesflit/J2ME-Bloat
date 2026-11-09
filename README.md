J2ME-Bloat

<p align="center">
  <img src="screenshots/screen.jpg" width="180">
</p><h3 align="center">Java ME Gaming, Ready to Go.</h3><p align="center">
  <a href="https://github.com/mesflit/J2ME-Bloat">
    <img src="https://img.shields.io/badge/GitHub-Repository-181717?logo=github" alt="GitHub">
  </a>
  <a href="https://github.com/mesflit/J2ME-Bloat/releases">
    <img src="https://img.shields.io/github/v/release/mesflit/J2ME-Bloat?display_name=tag" alt="Latest Release">
  </a>
  <a href="https://github.com/mesflit/J2ME-Bloat/blob/master/LICENSE">
    <img src="https://img.shields.io/github/license/mesflit/J2ME-Bloat" alt="License">
  </a>
</p>J2ME-Bloat is an Android-based Java ME (J2ME) emulator derived from "J2ME Loader" (https://github.com/nikita36078/J2ME-Loader).

It allows you to play classic Java mobile games on modern Android devices, with built-in game support, virtual controls, customizable settings and compatibility features.

Features

- Java ME (J2ME) game emulation.
- Support for many 2D and 3D games.
- Built-in game collection.
- Games bundled directly into the APK.
- Automatic game installation on first launch.
- Virtual keyboard and touchscreen controls.
- Individual settings for each game.
- Screen scaling and display customization.
- Support for Mascot Capsule 3D games.

Bundled Games

J2ME-Bloat supports including games directly in the application package.

To add games to your own build, place your ".jar" files inside:

app/src/main/assets/games/

Example:

games/
├── Game1.jar
├── Game2.jar
├── Game3.jar
└── Game4.jar

During the build process, these files are packaged into the APK.

On the first launch, bundled games are automatically imported into the emulator's game library.

No manual JAR installation is required.

Build Instructions

Requirements

- Android Studio or Android SDK
- JDK compatible with the project
- Gradle wrapper

Build Debug APK

git clone https://github.com/mesflit/J2ME-Bloat.git
cd J2ME-Bloat

./gradlew :app:assembleOpenDebug

Build Release APK

./gradlew :app:assembleOpenRelease

Generated APK files:

app/build/outputs/apk/open/

Compatibility

J2ME-Bloat is designed to run Java ME games from classic mobile phones, including devices from:

- Nokia
- Sony Ericsson
- Samsung
- Motorola
- LG

Game compatibility depends on the original JAR, Java ME APIs and device-specific implementations.

Screenshots

<!-- Add your screenshots here -->Downloads

All releases and downloadable builds are available on GitHub.

<p align="center">
  <a href="https://github.com/mesflit/J2ME-Bloat/releases">
    <img src="https://img.shields.io/badge/Download-GitHub%20Releases-blue?style=for-the-badge&logo=github" alt="Download from GitHub">
  </a>
</p>Repository: https://github.com/mesflit/J2ME-Bloat

Releases: https://github.com/mesflit/J2ME-Bloat/releases

Credits

J2ME-Bloat is based on the open-source "J2ME Loader" (https://github.com/nikita36078/J2ME-Loader) project.

Special thanks to:

- "Nikita Shakarun" (https://github.com/nikita36078)
- "woesss" (https://github.com/woesss)
- "NaikSoftware" (https://github.com/NaikSoftware)

Thanks to all contributors who helped make Java ME emulation possible on Android.

License

This project retains the original project's Apache License 2.0 licensing terms.

See "LICENSE" (LICENSE) for details.
