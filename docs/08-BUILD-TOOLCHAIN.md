# OSS — Build Toolchain Baseline

## Initial implementation baseline
The initial project skeleton uses a conservative toolchain aligned with the previous OSS Android build setup:
- Android Gradle Plugin: **8.13.2**
- Gradle: **8.13**
- Kotlin: **2.3.21**
- Compose compiler Gradle plugin: **2.3.21**
- JDK: **17**
- compileSdk / targetSdk: **36**
- minSdk: **29** (Android 10)
- Application ID: **com.iamahsana.ossstudio**

Compose libraries are managed through the Compose BOM in `gradle/libs.versions.toml`. The BOM version is pinned centrally and must be confirmed by the first successful CI build before treating it as validated.

## Validation policy
Before changing a pinned version:
1. Check official compatibility matrices and release notes.
2. Update the version catalog in one controlled change.
3. Run CI unit tests and assembleDebug.
4. Verify APK installation and launch on the target phone.

This is an implementation baseline, not a claim that CI has already passed. CI must validate the complete dependency set.

## References
- Android Gradle Plugin compatibility: https://developer.android.com/build/releases/about-agp
- Kotlin Gradle Plugin compatibility: https://kotlinlang.org/docs/gradle-configure-project.html
- Compose compiler migration: https://kotlinlang.org/docs/compose-compiler-migration-guide.html
- Compose BOM: https://developer.android.com/develop/ui/compose/bom
- Gradle compatibility matrix: https://docs.gradle.org/current/userguide/compatibility.html
