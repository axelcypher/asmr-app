import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "de.axelcypher.asmr"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.axelcypher.asmr"
        minSdk = 26
        targetSdk = 37
        // In CI fortlaufend (Run-Nummer), damit der Auto-Update-Kanal Versionen vergleichen kann.
        versionCode = System.getenv("ASMR_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("ASMR_VERSION_NAME") ?: "0.1.0-dev"
    }

    // Release-Signatur: in CI Ã¼ber Umgebungsvariablen (siehe .github/workflows), lokal Ã¼ber
    // ~/.keystores/asmr-app-keystore.properties. Fehlt beides, bleibt der Release unsigniert.
    val localSigning = File(System.getProperty("user.home"), ".keystores/asmr-app-keystore.properties")
        .takeIf { it.isFile }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
    val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE") ?: localSigning?.getProperty("storeFile")

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: localSigning?.getProperty("storePassword")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: localSigning?.getProperty("keyAlias")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: localSigning?.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Erst wieder einschalten, wenn R8-Regeln für Ktor/Serialization auf einem Gerät geprüft sind.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
