plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
}

// Release builds get their version from CI (-PappVersion=1.x). Local builds default to 0.1,
// below every release, so the in-app updater and Android can install releases over them.
val appVersion = (findProperty("appVersion") as String?) ?: "0.1"
require(Regex("""\d+\.\d""").matches(appVersion)) {
    "appVersion must look like 5.7, got $appVersion"
}
val appVersionCode = appVersion.split(".").let { (major, minor) ->
    major.toInt() * 10 + minor.toInt()
}

android {
    namespace = "com.vrec.app"
    compileSdk = 37
    // Needed by the source-built shell-side recorder jar (d8 36.0.0).
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.vrec.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: appVersionCode
        versionName = appVersion + ((findProperty("appVersionSuffix") as String?) ?: "")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }


    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Match n2k02theme and n2k02updater's Material 3 ABI. A stale 1.3.0 runtime
    // crashes when users open ModalBottomSheet with NoSuchMethodError.
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.material:material-icons-extended")
    // Apache-2.0 alternative of libadb; TLS pairing and X509 identity dependencies.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    implementation("org.conscrypt:conscrypt-android:2.7.0")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

 // Opt-in compile-only shared library evaluation; no production runtime cutover.
dependencies {
    implementation("dev.n2k02:theme:0.1.4")
        implementation("dev.n2k02:updater:0.1.19")
}


// Build the Apache-2.0 audio-only scrcpy/JemRec-derived service FROM SOURCE.
// The jar is bundled in the APK; no runtime download or companion app.
val daemonDir = rootProject.file("shellserver")
val daemonJar = File(daemonDir, "vrec-capture.jar")
val buildVrecShellDaemon = tasks.register<Exec>("buildVrecShellDaemon") {
    workingDir = daemonDir
    commandLine("bash", "build.sh")
    inputs.dir(File(daemonDir, "src"))
    inputs.file(File(daemonDir, "build.sh"))
    outputs.file(daemonJar)
}
val bundleVrecShellDaemon = tasks.register<Copy>("bundleVrecShellDaemon") {
    dependsOn(buildVrecShellDaemon)
    from(daemonJar)
    into(layout.projectDirectory.dir("src/main/assets"))
}
// Both classpaths must package the Material 3 ABI used by n2k02theme/updater.
// Compilation alone is not enough: a mismatched runtime causes NoSuchMethodError
// only when the recording overflow's ModalBottomSheet is first shown.
val verifyComposeMaterial3RuntimeAbi = tasks.register("verifyComposeMaterial3RuntimeAbi") {
    group = "verification"
    description = "Fail the build if Material 3's compile and runtime versions diverge"
    doLast {
        val expected = "1.5.0-beta01"
        listOf("debugCompileClasspath", "debugRuntimeClasspath").forEach { configurationName ->
            val selectedVersions = configurations.getByName(configurationName)
                .incoming.resolutionResult.allComponents
                .mapNotNull { it.moduleVersion }
                .filter { it.group == "androidx.compose.material3" && it.name == "material3" }
                .map { it.version }
                .distinct()
            check(selectedVersions == listOf(expected)) {
                "$configurationName resolved Material 3 $selectedVersions instead of $expected; " +
                    "this can crash ModalBottomSheet with NoSuchMethodError."
            }
        }
        println("Compose Material 3 compile/runtime ABI verified at $expected")
    }
}

tasks.named("preBuild") {
    dependsOn(bundleVrecShellDaemon)
    dependsOn(verifyComposeMaterial3RuntimeAbi)
}
