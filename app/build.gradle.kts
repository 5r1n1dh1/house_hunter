plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.secrets)
}

/**
 * Reads the Maps key, in order of precedence:
 *  1. the `MAPS_API_KEY` environment variable - this is how CI supplies it
 *     (`env:` on the assembleRelease step), and
 *  2. `.env`, then `.env.example` for local builds.
 *
 * The environment variable MUST come first. A previous version read only the
 * `.env` files, so CI - which has no `.env` because it is gitignored - always
 * built with the `.env.example` placeholder and the app reported "API key not
 * configured" even though the secret was set.
 *
 * The secrets plugin fills manifest placeholders only and does not generate
 * `BuildConfig` fields, so `MapAvailability` needs the value here as well.
 */
val mapsApiKey: String = run {
    providers.environmentVariable("MAPS_API_KEY").orNull?.takeIf { it.isNotBlank() }?.let { return@run it }

    val envFile = rootProject.file(".env")
    val example = rootProject.file(".env.example")
    listOf(envFile, example)
        .firstOrNull { it.exists() }
        ?.readLines()
        ?.firstOrNull { it.trimStart().startsWith("MAPS_API_KEY=") }
        ?.substringAfter('=')
        ?.trim()
        .orEmpty()
}

android {
    namespace = "com.househunter.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.househunter.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
    }

    signingConfigs {
        // Only used when debug.keystore is actually present. CI has no such file,
        // so applying it unconditionally would fail the build before compiling.
        if (file("${rootDir}/debug.keystore").exists()) {
            create("debugConfig") {
                storeFile = file("${rootDir}/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("debugConfig")?.let { signingConfig = it }
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.jsoup)
    implementation(libs.coil.compose)

    implementation(libs.play.services.maps)
    implementation(libs.maps.compose)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

secrets {
    propertiesFileName = ".env"
    defaultPropertiesFileName = ".env.example"
}