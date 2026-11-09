# OSS — Build Toolchain Baseline

## Initial implementation baseline
The initial project skeleton uses a conservative, mutually compatible toolchain:
- Android Gradle Plugin: **8.13.2**
- Gradle: **8.13**
- Kotlin: **2.3.21**
- Compose compiler Gradle plugin: **2.3.21**
- Compose BOM: **2025.10.00**
- Activity Compose: **1.11.0**
- JDK: **17**
- compileSdk / targetSdk: **36**
- minSdk: **29** (Android 10)
- Application ID: **com.iamahsana.ossstudio**

The Compose BOM is deliberately pinned to a release whose Compose libraries do not force an AGP 9 / API 37 toolchain migration. Compose BOM **2026.09.00** maps to Compose 1.12.x, which requires AGP 9.1+ and compileSdk 37; using it with AGP 8.13.2 and compileSdk 36 fails dependency metadata validation. We will upgrade AGP, Gradle, SDK, and Compose together only after checking their compatibility matrix and validating CI.

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
