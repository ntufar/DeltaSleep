import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Signing: read keystore.properties locally; in CI values come from env vars.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { Properties().apply { load(it.inputStream()) } }

android {
    namespace = "io.github.ntufar.deltasleep"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ntufar.deltasleep"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.4"
    }

    signingConfigs {
        create("release") {
            storeFile = (keystoreProps?.getProperty("storeFile")
                ?: System.getenv("KEYSTORE_PATH"))
                ?.let { rootProject.file(it) }
            storePassword = keystoreProps?.getProperty("storePassword")
                ?: System.getenv("KEYSTORE_PASSWORD")
            keyAlias = keystoreProps?.getProperty("keyAlias")
                ?: System.getenv("KEY_ALIAS")
            keyPassword = keystoreProps?.getProperty("keyPassword")
                ?: System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        // Store .so files uncompressed so the OS can mmap them directly,
        // which is required for 16 KB page-size alignment (Android 15+).
        jniLibs.useLegacyPackaging = false
    }
}

// Builds Rust DSP library for arm64-v8a and x86_64.
// Requires: cargo install cargo-ndk  &&  rustup target add aarch64-linux-android x86_64-linux-android
tasks.register<Exec>("buildRustDsp") {
    group = "build"
    workingDir(rootProject.file("dsp"))

    // Resolve NDK home: prefer ANDROID_NDK_HOME env var, fall back to sdk/ndk/<latest>.
    val ndkHome: String = System.getenv("ANDROID_NDK_HOME")
        ?: run {
            val sdkDir = File(rootProject.file("local.properties")
                .takeIf { it.exists() }
                ?.readLines()
                ?.firstOrNull { it.startsWith("sdk.dir") }
                ?.substringAfter("=")
                ?.trim()
                ?: System.getenv("ANDROID_HOME")
                ?: error("Android SDK not found. Set sdk.dir in local.properties or ANDROID_HOME.")
            )
            val ndkDir = File(sdkDir, "ndk")
            ndkDir.listFiles()
                ?.maxByOrNull { it.name }
                ?.absolutePath
                ?: error("No NDK found under $ndkDir. Install one via Android Studio → SDK Manager → SDK Tools.")
        }

    environment("ANDROID_NDK_HOME", ndkHome)
    // Use the absolute path so Gradle finds cargo regardless of whether
    // ~/.cargo/env was sourced before the daemon started.
    val cargo = "${System.getProperty("user.home")}/.cargo/bin/cargo"
    commandLine(
        cargo, "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", "../app/src/main/jniLibs",
        "build", "--release"
    )
    inputs.dir(rootProject.file("dsp/src"))
    inputs.file(rootProject.file("dsp/Cargo.toml"))
    outputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))
}

tasks.named("preBuild") {
    dependsOn("buildRustDsp")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
