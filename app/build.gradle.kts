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
        versionCode = 3
        versionName = "0.3"
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

/**
 * Fails the build on a user-facing string typed straight into a composable.
 *
 * This exists because Android's own `HardcodedText` lint only reads XML layouts — it has nothing
 * to say about `Text("Cancel")`, which is the only way this app writes UI. Without a check of our
 * own, the next screen written would silently be English-only and nothing would complain.
 *
 * Deliberately narrow: it looks at the two constructs that actually put words in front of someone,
 * and leaves alone the places a bare string is legitimate (Compose animation labels, log messages,
 * file extensions). Something that slips past this is still caught by reading the diff.
 */
val checkNoHardcodedUiStrings by tasks.registering {
    group = "verification"
    description = "Fails if a composable passes a literal where a string resource belongs."
    val sources = fileTree("src/main/java") { include("**/*.kt") }
    inputs.files(sources)
    // No real output; the up-to-date marker keeps repeat runs cheap.
    val stamp = layout.buildDirectory.file("tmp/hardcoded-ui-strings.ok")
    outputs.file(stamp)
    doLast {
        val patterns = listOf(
            Regex("\\bText\\(\\s*\""),
            Regex("\\bcontentDescription\\s*=\\s*\""),
        )
        val offenders = sources.files.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> patterns.any { it.containsMatchIn(line) } }
                .map { (i, line) -> "${file.relativeTo(projectDir)}:${i + 1}: ${line.trim()}" }
        }
        if (offenders.isNotEmpty()) {
            error(
                "Hardcoded UI strings — move these to res/values/strings.xml and read them " +
                    "with stringResource():\n" + offenders.joinToString("\n"),
            )
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

tasks.named("check") { dependsOn(checkNoHardcodedUiStrings) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Only for AppCompatDelegate.setApplicationLocales, which is how the language row in Settings
    // applies a choice. On Android 13+ that call forwards to the framework's per-app language, so
    // our picker and the one in Android's Settings are one value; below 33 AppCompat is what stores
    // the choice and re-applies it on launch — and that machinery hangs off AppCompatActivity,
    // which is why MainActivity extends it despite the UI being entirely Compose. minSdk is 26, so
    // that lower branch covers 26 to 32.
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.12.4")
    // Browsing and creating inside the folder the user granted us: SAF trees are a document-id
    // tree, not a path, and this wraps the DocumentsContract calls that walk one.
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Custom Tabs, for the Support dialog's links. The browser fetches in its own process, so
    // this buys the in-app look without the app ever needing INTERNET — see [openCustomTab].
    implementation("androidx.browser:browser:1.8.0")

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
