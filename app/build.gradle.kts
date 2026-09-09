import java.util.Properties

// Release signing credentials: a gitignored keystore.properties if present, else the
// environment. Missing either, the release build still runs unsigned so R8 and resource
// shrinking stay verifiable on a machine without the key.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun credential(property: String, variable: String): String? =
    keystoreProps.getProperty(property) ?: System.getenv(variable)

val signingStore = credential("storeFile", "ANDROID_KEYSTORE_FILE")
val signingStorePassword = credential("storePassword", "ANDROID_KEYSTORE_PASSWORD")
val signingKeyAlias = credential("keyAlias", "ANDROID_KEY_ALIAS")
val signingKeyPassword = credential("keyPassword", "ANDROID_KEY_PASSWORD")

val canSignRelease = !signingStore.isNullOrBlank() &&
    !signingStorePassword.isNullOrBlank() &&
    !signingKeyAlias.isNullOrBlank() &&
    !signingKeyPassword.isNullOrBlank() &&
    rootProject.file(signingStore).exists()

if (!canSignRelease && gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
    logger.warn(
        "\n*** Release build will be UNSIGNED - no usable keystore.properties or " +
            "ANDROID_KEYSTORE_* environment. Do not publish this artifact. ***\n"
    )
}

val verMajor = 0
val verMinor = 4
val verPatch = 0
val verBuild = 4

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "com.ninecsdev.wallpaperchanger"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ninecsdev.wallpaperchanger"
        minSdk = 33
        targetSdk = 37

        versionCode = verMajor * 1_000_000 + verMinor * 10_000 + verPatch * 100 + verBuild
        versionName = "${verMajor}.${verMinor}.${verPatch}"
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = rootProject.file(signingStore!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            manifestPlaceholders["appLabel"] = "@string/app_name"
        }
        debug {
            versionNameSuffix= "-debug"
            applicationIdSuffix= ".debug"

            manifestPlaceholders["appLabel"] = "Wallpaper (Dev)"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // The atmosphere engine's frame scrub is debug-only and gates on BuildConfig.DEBUG.
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.palette)
    implementation(libs.material)
    testImplementation(libs.junit)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}