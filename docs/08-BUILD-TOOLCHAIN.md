# OSS — Build Toolchain Baseline

This baseline favors a stable, mutually compatible toolchain over adopting every newest release independently. Versions are pinned; dynamic plugin versions are prohibited.

## Initial baseline
- Android Gradle Plugin: **9.1.0**
- Gradle Wrapper: **9.3.1**
- Kotlin: **2.4.10**
- Compose Compiler Gradle plugin: **2.4.10** (same version as Kotlin)
- Jetpack Compose stable BOM: **2026.09.00**
- JDK: **17**
- compileSdk / targetSdk: **36**
- minSdk: **29** (Android 10)
- Application ID candidate: **com.iamahsana.ossstudio**

## Compatibility rationale
Kotlin's published compatibility matrix lists Kotlin Gradle Plugin 2.4.0–2.4.10 as compatible with Gradle through 9.5.0 and Android Gradle Plugin through 9.1.0. Android Gradle Plugin 9.1.0 requires Gradle 9.3.1. This pairing stays within the published compatibility range.

The Compose compiler plugin is versioned with Kotlin 2.x and should be applied to each Compose module. The Compose BOM manages compatible Compose library versions independently from the compiler plugin.

## Upgrade policy
Before changing any pinned version:
1. Check official compatibility matrices.
2. Check release notes and known regressions.
3. Update the version catalog in one controlled change.
4. Run CI unit tests and assembleDebug.
5. Verify APK installation and launch on the target phone.

Do not upgrade AGP, Gradle, Kotlin, and Compose independently without checking their compatibility.

## References
- Android Gradle Plugin compatibility and release information: https://developer.android.com/build/releases/about-agp
- Kotlin Gradle Plugin compatibility: https://kotlinlang.org/docs/gradle-configure-project.html
- Compose compiler migration: https://kotlinlang.org/docs/compose-compiler-migration-guide.html
- Compose BOM: https://developer.android.com/develop/ui/compose/bom
- Gradle compatibility matrix: https://docs.gradle.org/current/userguide/compatibility.html

This is the initial pinned baseline. CI validation is required before it is considered a verified build.
