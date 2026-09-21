import java.util.Properties
import org.gradle.api.tasks.testing.Test

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
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ntufar.deltasleep"
        minSdk = 26
        targetSdk = 36
        versionCode = 29
        versionName = "0.2.12"
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
        debug {
            applicationIdSuffix = ".debug"
        }
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

    testOptions {
        unitTests {
            // Required so Robolectric picks up the merged manifest (DeltaSleepApp)
            // and resources when running Compose UI tests on the JVM.
            isIncludeAndroidResources = true
        }
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
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    // Compose UI tests running on the JVM via Robolectric (TrendsScreenTest).
    // Test-only: never packaged into the APK, no effect on the offline build.
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.17")

// Robolectric downloads android-all jars at test runtime and keeps a
// lock/cache under user.home. ROBO_HOME redirects that to a writable dir
// (only needed on machines with a read-only HOME); the proxy forwarding
// below is a no-op unless *_proxy env vars are set.
tasks.withType<Test> {
    System.getenv("ROBO_HOME")?.let { systemProperty("user.home", it) }
    val proxy = (System.getenv("https_proxy") ?: System.getenv("HTTPS_PROXY") ?: "")
        .removePrefix("http://").removePrefix("https://")
    if (proxy.isNotBlank()) {
        val host = proxy.substringBefore(":")
        val port = proxy.substringAfter(":").substringBefore("/")
        systemProperty("http.proxyHost", host)
        systemProperty("http.proxyPort", port)
        systemProperty("https.proxyHost", host)
        systemProperty("https.proxyPort", port)
        systemProperty("http.nonProxyHosts", "localhost|127.0.0.1")
        systemProperty("https.nonProxyHosts", "localhost|127.0.0.1")
    }
}
}
