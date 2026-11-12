plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val ossReleaseStoreFile = providers.gradleProperty("ossReleaseStoreFile").orNull
    ?: System.getenv("OSS_RELEASE_STORE_FILE")
val ossReleaseStorePassword = providers.gradleProperty("ossReleaseStorePassword").orNull
    ?: System.getenv("OSS_RELEASE_STORE_PASSWORD")
val ossReleaseKeyAlias = providers.gradleProperty("ossReleaseKeyAlias").orNull
    ?: System.getenv("OSS_RELEASE_KEY_ALIAS")
val ossReleaseKeyPassword = providers.gradleProperty("ossReleaseKeyPassword").orNull
    ?: System.getenv("OSS_RELEASE_KEY_PASSWORD")

val ossReleaseSigningConfigured = listOf(
    ossReleaseStoreFile,
    ossReleaseStorePassword,
    ossReleaseKeyAlias,
    ossReleaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.iamahsana.ossstudio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.iamahsana.ossstudio"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (ossReleaseSigningConfigured) {
                storeFile = file(requireNotNull(ossReleaseStoreFile))
                storePassword = requireNotNull(ossReleaseStorePassword)
                keyAlias = requireNotNull(ossReleaseKeyAlias)
                keyPassword = requireNotNull(ossReleaseKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (ossReleaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        doFirst {
            check(ossReleaseSigningConfigured) {
                "Release signing is not configured. Supply OSS_RELEASE_STORE_FILE, OSS_RELEASE_STORE_PASSWORD, OSS_RELEASE_KEY_ALIAS, and OSS_RELEASE_KEY_PASSWORD."
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    // Provisional dependency-resolution probe; no RootEncoder APIs are used yet.
    implementation(libs.rootencoder.library)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
