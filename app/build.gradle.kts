import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing comes from the environment (CI) or an untracked keystore.properties on a
// workstation. With neither, the release build is simply unsigned — fine for a compile check —
// and `fastlane android release` refuses up front instead of producing something unshippable.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(name)
val releaseStoreFile = secret("RELEASE_STORE_FILE")

android {
    namespace = "com.vivekkaushik.wrtpulse"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.vivekkaushik.wrtpulse"
        minSdk = 28
        targetSdk = 36
        // Overridable so CI can stamp a release: -PversionCode=<run number> -PversionName=<tag>.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
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
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.jsch)
    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Android stubs org.json in JVM tests; use the real implementation for parser tests.
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}