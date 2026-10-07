import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Signing
//
// The repository ships a *demo* signing identity (PEM cert + key) so that
// anybody can produce an installable release APK. `scripts/make-keystore.sh`
// turns those PEM files into the PKCS#12 keystore AGP expects. Replace the
// PEM files with your own identity before publishing to a store.
// ---------------------------------------------------------------------------
val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
val releaseKeystore = keystoreProperties.getProperty("storeFile")?.let { rootProject.file("keystore/$it") }
val hasReleaseKeystore = releaseKeystore?.exists() == true

val versionCodeOverride = (project.findProperty("airwhispers.versionCode") as String?)?.toInt() ?: 1
val versionNameOverride = (project.findProperty("airwhispers.versionName") as String?) ?: "1.0.0"
val defaultBackendUrl = (project.findProperty("airwhispers.defaultBackendUrl") as String?) ?: ""

android {
    namespace = "com.airwhispers"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airwhispers"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeOverride
        versionName = versionNameOverride
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "hi")

        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"$defaultBackendUrl\"")
    }

    // `standalone` ships a self-contained app (WebSocket relay only, no third
    // party services). `fcm` adds Firebase Cloud Messaging support and is only
    // useful when android/app/google-services.json is present.
    flavorDimensions += "push"
    productFlavors {
        create("standalone") {
            dimension = "push"
            isDefault = true
        }
        create("fcm") {
            dimension = "push"
            versionNameSuffix = null
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystore
                storeType = keystoreProperties.getProperty("storeType") ?: "PKCS12"
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            // R8 is intentionally off for v1: the APK stays debuggable-with-symbols
            // and reproducible across AGP versions. Enable before a store release.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                logger.lifecycle("AirWhispers: release keystore not found, signing the release build with the debug key.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/NOTICE",
                "/META-INF/NOTICE.txt",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Lint is the only check that can catch device-behaviour mistakes (calling a
        // newer API without a version guard, missing permissions, foreground-service
        // type misuse) without a physical phone, so its errors block the build.
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        xmlReport = true
        htmlReport = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // Cloud push (optional transport). Only the `fcm` flavor gets it.
    "fcmImplementation"(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// The google-services plugin is only applied when a config file exists so that
// the `standalone` flavor always builds with zero external configuration.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
