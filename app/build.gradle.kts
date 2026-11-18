import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Release signing lives outside the repo: ~/keystores/novelo-keystore.properties (or keystore.properties at the root).
val signingProps = Properties().apply {
    val candidates = listOf(
        rootProject.file("keystore.properties"),
        File(System.getProperty("user.home"), "keystores/novelo-keystore.properties"),
    )
    candidates.firstOrNull { it.exists() }?.inputStream()?.use { load(it) }
}

// The on-device model (LFM3 through llama.cpp, app/src/main/cpp). Built only with -Pnovelo.llama=<dir>,
// a llama.cpp checkout that lives just for the build (tools/release.sh fetches it and deletes it).
val llamaDir = (findProperty("novelo.llama") as String?)?.takeIf { it.isNotBlank() }?.let { File(it) }
val withNative = llamaDir != null && File(llamaDir, "CMakeLists.txt").exists()

// The Novelo's own intelligence lives on the novelo-server. Its address is not
// a secret, but it isn't fixed in the repo: ~/.config/novelo/server.env (NOVELO_SERVER_URL=https://…)
// or the env var NOVELO_SERVER_URL, read at build time. A build without it has only her own
// intelligence (the model on the phone, or her API) and the backup in a folder.
val noveloServer: String = (System.getenv("NOVELO_SERVER_URL")?.trim()?.takeIf { it.isNotEmpty() }
    ?: File(System.getProperty("user.home"), ".config/novelo/server.env").takeIf { it.isFile }
        ?.readLines()?.firstOrNull { it.startsWith("NOVELO_SERVER_URL=") }?.substringAfter('=')?.trim().orEmpty())
    .trimEnd('/')
// Speech, photos and the backup's pieces go over it: https always (plain http only to this machine, for tests).
require(noveloServer.isEmpty() || noveloServer.startsWith("https://") || Regex("^http://(127\\.0\\.0\\.1|localhost|10\\.0\\.2\\.2)(:\\d+)?(/.*)?$").matches(noveloServer)) {
    "NOVELO_SERVER_URL must be an https:// address"
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.villamediana.novelo"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.villamediana.novelo"
        minSdk = 29
        targetSdk = 36
        versionCode = 117
        versionName = "1.79.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "HAS_LOCAL_ENGINE", withNative.toString())
        buildConfigField("String", "NOVELO_SERVER", "\"" + noveloServer + "\"")
        // Phones only (ML Kit's OCR alone ships ~11 MB per ABI).
        ndk { abiFilters += listOf("arm64-v8a") }
        if (withNative) {
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DLLAMA_DIR=${llamaDir!!.absolutePath}", "-DANDROID_STL=c++_shared")
                    cppFlags += listOf("-std=c++17")
                }
            }
        }
    }

    if (withNative) {
        ndkVersion = "29.0.14206865"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
            }
        }
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
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // Same choice as FreeSong: the release runs the bytecode the tests run.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Never the debug key: an APK signed with another key can't update the installed app.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("pt", "es", "en")
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/README.md",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
            )
        }
        jniLibs { useLegacyPackaging = false }
    }

    sourceSets.getByName("debug").assets.directories.add("$projectDir/schemas")

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                )
                it.maxHeapSize = "3g"
                it.systemProperty("novelo.shots", System.getenv("NOVELO_SHOTS") ?: "")
                // The app against a novelo-server really running (NoveloLiveTest); empty: that test is skipped.
                it.systemProperty("novelo.e2e", System.getenv("NOVELO_E2E_URL") ?: "")
                // The unit tests run without the Novelo's server (never the real one: no phone registered there by a test).
                it.systemProperty("novelo.server", "")
                it.testLogging { events("failed", "skipped"); showStandardStreams = true }
            }
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { it.project == project && it.name.contains("Release") && (it.name.startsWith("assemble") || it.name.startsWith("package") || it.name.startsWith("bundle")) }
    if (wantsRelease && signingProps.getProperty("storeFile") == null) {
        throw GradleException("Release signing keystore missing: ~/keystores/novelo-keystore.properties")
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)

    implementation(libs.androidx.core)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    // A digital (ou a senha do celular) pra ver o que é sensível, quando ela quer.
    implementation(libs.androidx.biometric)
    // Holding an item and dragging it (steps, the tasks list).
    implementation(libs.reorderable)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.text)
    // Reading the Novelo on the computer's code (on the phone, no network).
    implementation(libs.mlkit.barcode)
    implementation(libs.exif)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
