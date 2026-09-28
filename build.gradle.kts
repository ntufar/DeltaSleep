// NOTE: no org.jetbrains.kotlin.android here — AGP 9 provides Kotlin itself
// (built-in Kotlin, same 2.2.x line). Only the compose compiler plugin and
// KSP need explicit versions; they track AGP's built-in KGP.
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}
