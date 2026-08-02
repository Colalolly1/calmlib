import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.calmlib.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.calmlib.reader"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "0.16.0"
    }

    // Release signing. Keystore details come from keystore.properties (git-ignored)
    // or environment variables — never from this file, which is public.
    // Users' devices refuse an update signed with a different key, so the
    // release keystore must be kept and backed up: losing it strands everyone.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
    val storePathValue = keystoreProps.getProperty("storeFile") ?: System.getenv("CALMLIB_STORE_FILE")

    signingConfigs {
        if (storePathValue != null && rootProject.file(storePathValue).exists()) {
            create("release") {
                storeFile = rootProject.file(storePathValue)
                storePassword = keystoreProps.getProperty("storePassword") ?: System.getenv("CALMLIB_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("keyAlias") ?: System.getenv("CALMLIB_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("keyPassword") ?: System.getenv("CALMLIB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    lint {
        // NewApi caught a genuine crash-on-every-device bug (readNBytes is API
        // 33+ while minSdk is 31). Never ship past it again.
        warningsAsErrors = false
        abortOnError = true
        error += setOf("NewApi", "InlinedApi")
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
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // PDFBox port for Android. Used only by the PDF→EPUB converter — text extraction
    // and PDF metadata. Ships its own fonts/resources so the APK grows ~6MB.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
