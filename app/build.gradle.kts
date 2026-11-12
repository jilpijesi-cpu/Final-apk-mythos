import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Nama berkas APK dibedakan per patch (hasil: Mythos-v83-patch13-debug.apk / -release.apk).
// applicationId TETAP com.mythos.app agar Client ID OAuth Google tetap cocok.
base.archivesName.set("Mythos-v83-patch13")

val releaseKeystorePropertiesFile = rootProject.file("../release-keystore/keystore.properties")
val releaseKeystoreProperties = Properties()
if (releaseKeystorePropertiesFile.exists()) {
    FileInputStream(releaseKeystorePropertiesFile).use { releaseKeystoreProperties.load(it) }
}
val mythosVersionCode = (providers.gradleProperty("mythosVersionCode").orNull ?: error("mythosVersionCode belum diisi di gradle.properties")).toInt()
val mythosVersionName = providers.gradleProperty("mythosVersionName").orNull ?: error("mythosVersionName belum diisi di gradle.properties")
val useDebugKeystoreForRelease = providers.gradleProperty("useDebugKeystoreForRelease").orNull == "true"
val debugGoogleClientId = providers.gradleProperty("mythosDebugGoogleClientId").orNull.orEmpty()
val releaseGoogleClientId = providers.gradleProperty("mythosReleaseGoogleClientId").orNull.orEmpty()

android {
    namespace = "com.mythos.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mythos.app"
        minSdk = 26
        targetSdk = 35
        versionCode = mythosVersionCode
        versionName = mythosVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystorePropertiesFile.exists()) {
            create("release") {
                // Path absolut dari mesin lain tidak berlaku: bila tidak ada, cari berkas .jks di folder yang sama dengan keystore.properties.
                val configuredStore = File(releaseKeystoreProperties["storeFile"] as String)
                storeFile = if (configuredStore.isAbsolute && configuredStore.exists()) configuredStore
                else File(releaseKeystorePropertiesFile.parentFile, configuredStore.name)
                storePassword = releaseKeystoreProperties["storePassword"] as String
                keyAlias = releaseKeystoreProperties["keyAlias"] as String
                keyPassword = releaseKeystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (useDebugKeystoreForRelease) {
                signingConfig = signingConfigs.getByName("debug")
            } else if (releaseKeystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            val effectiveReleaseClientId = if (useDebugKeystoreForRelease) debugGoogleClientId else releaseGoogleClientId
            buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$effectiveReleaseClientId\"")
        }
    }

    buildTypes.getByName("debug") {
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$debugGoogleClientId\"")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // Dns kustom untuk menolak alamat privat saat connect (anti DNS rebinding)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303")

    // Tes instrumented (perlu emulator/perangkat): Keystore tidak ada di JVM biasa.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
