import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.singular.recorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.singular.recorder"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Keep the git sha out of the APK, so an F-Droid rebuild of the tagged commit can
            // match the published binary byte-for-byte (the lesson RubberRing 0.3 paid for).
            vcsInfo { include = false }
        }
    }

    buildFeatures {
        compose = true
        // For the version shown on the About screen. Generated from the constants above only —
        // unlike vcsInfo it embeds nothing build-specific, so it stays reproducible.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.12.4")
    // Browsing and creating inside the folder the user granted us: SAF trees are a document-id
    // tree, not a path, and this wraps the DocumentsContract calls that walk one.
    implementation("androidx.documentfile:documentfile:1.0.1")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // 2.9.x is compiled against API 36; 2.10+/2.11 require compileSdk 37.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Local JVM unit tests (the WAV reader/writer is pure Kotlin — no Android framework needed).
    testImplementation("junit:junit:4.13.2")
}
