plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.secrets)
}

/**
 * Reads the Maps key from the same `.env` the secrets plugin consumes.
 *
 * The secrets plugin fills **manifest placeholders only**; it does not generate
 * `BuildConfig` fields. `MapAvailability` needs the value in Kotlin, so it is
 * read here once and used for both. Falls back to the placeholder from
 * `.env.example`, which `MapAvailability` recognises as "no key configured" -
 * so a machine without a key builds and simply runs without maps.
 */
val mapsApiKey: String = run {
    val envFile = rootProject.file(".env")
    val example = rootProject.file(".env.example")
    val source = listOf(envFile, example).firstOrNull { it.exists() } ?: return@run ""
    source.readLines()
        .firstOrNull { it.trimStart().startsWith("MAPS_API_KEY=") }
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