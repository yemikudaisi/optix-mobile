import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.etracker.tracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.etracker.tracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "0.8.1"
    }

    // Release signing key: kept OUTSIDE the repo (default ~/.optix-signing/keystore.properties, or the
    // OPTIX_SIGNING env var pointing at a properties file with storeFile, storePassword, keyAlias,
    // keyPassword). Installed phones only accept updates signed with the same key: back it up.
    val signingProps = Properties().apply {
        val f = file(System.getenv("OPTIX_SIGNING") ?: "${System.getProperty("user.home")}/.optix-signing/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // not minified yet: the release build stays byte-for-byte the code the debug builds were tested with
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.VERSION_CODE / VERSION_NAME for the update check
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // fused location + QR scanning (Google Play services; no camera permission needed)
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // serves the bundled Situation map page over https://appassets.androidplatform.net
    implementation("androidx.webkit:webkit:1.12.1")

    // encrypted storage for the device token
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // plain JVM unit tests (app/src/test): ./gradlew :app:testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
}
