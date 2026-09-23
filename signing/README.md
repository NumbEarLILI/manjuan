# CI signing key

`manjuan-ci.jks` signs 漫卷 debug APKs (`com.numbear.manjuan`). Alias: `manjuan`. Passwords are in `ci-signing.properties` in this directory.

Local and CI `./gradlew :app:assembleDebug` both use this keystore. GitHub runners no longer fall back to `~/.android/debug.keystore`, so consecutive prerelease APKs share one signature and can overwrite each other.

This material is for **private-repo sideload** only. It is **not** a Play Store upload key and is not for Google Play App Signing.

If this repository becomes public, rotate the key and move the keystore and passwords out of git before the old material is exposed.
