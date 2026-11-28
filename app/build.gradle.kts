import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Penandatanganan rilis: data kunci dibaca dari keystore.properties di folder proyek (lihat keystore.properties.example).
// File itu dan keystore-nya TIDAK boleh masuk git. Build APK (assemble/bundle/install) SELALU butuh kunci ini,
// supaya APK hasilnya bisa menimpa versi lama (2.40). Tes unit dan lint tidak membutuhkannya.
// Pengecualian sadar (mis. CI tanpa rahasia): tambahkan -PallowUnsigned=true.
val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.isFile) keystoreFile.inputStream().use { load(it) }
}
val keystoreProblem: String? = when {
    !keystoreFile.isFile ->
        "keystore.properties tidak ditemukan di ${keystoreFile.path}."
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").any { keystoreProps.getProperty(it).isNullOrBlank() } ->
        "keystore.properties belum lengkap: storeFile, storePassword, keyAlias, dan keyPassword wajib diisi."
    !rootProject.file(keystoreProps.getProperty("storeFile")).isFile ->
        "File keystore '${keystoreProps.getProperty("storeFile")}' (storeFile di keystore.properties) tidak ada."
    else -> null
}
val hasReleaseKey = keystoreProblem == null

// Package aplikasi ini tetap com.build.mythos agar redirect OAuth konsisten.
val appId = "com.build.mythos"
// Client ID OAuth Google harus bertipe Android dan terikat ke package com.build.mythos
// serta SHA-1 keystore penandatangan aplikasi ini. Nilai dibaca dari gradle.properties.
val googleClientId: String = (project.findProperty("mythosGoogleClientId") as String?)?.trim().orEmpty()
logger.lifecycle("MYTHOS applicationId = $appId")

android {
    namespace = "com.build.mythos"
    compileSdk = 34

    defaultConfig {
        // applicationId menentukan apakah APK ini MEMPERBARUI instalasi lama atau dipasang sebagai aplikasi terpisah (F-07).
        // Package/versionCode/sertifikat divalidasi lagi oleh ApkVerify saat pasang.
        applicationId = appId
        minSdk = 26
        targetSdk = 34
        versionCode = 76
        versionName = "2.61"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val escapedGoogleClientId = googleClientId.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$escapedGoogleClientId\"")
    }
    buildFeatures { buildConfig = true }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                setProperty("storePassword", keystoreProps.getProperty("store" + "Password"))
                keyAlias = keystoreProps.getProperty("keyAlias")
                setProperty("keyPassword", keystoreProps.getProperty("key" + "Password"))
            }
        }
    }

    buildTypes {
        // Debug memakai keystore yang sama bila keystore.properties ada: tanpa ini tiap mesin (HP, GitHub Actions)
        // menandatangani dengan debug.keystore sendiri, dan Android menolak upgrade di atas versi dari mesin lain.
        debug {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else null
            if (!hasReleaseKey) {
                logger.warn("keystore.properties tidak ditemukan: APK rilis akan TIDAK ditandatangani dan tidak bisa dipasang.")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// APK membawa SOURCE dirinya sendiri (assets/mythos-source.zip), dibuat tiap build dari berkas proyek ini.
val packSource by tasks.registering(Zip::class) {
    archiveFileName.set("mythos-source.zip")
    from(rootProject.projectDir) {
        exclude(
            "build/**", "app/build/**", ".gradle/**", ".git/**", ".idea/**", "**/.DS_Store",
            "keystore.properties", "local.properties", "**/*.jks", "**/*.keystore", "**/*.p12", "**/*.apk", "**/*.aab",
            "**/*.pfx", "**/*.pem", "**/*.key", "**/*.ppk", "**/*.gpg",
            "**/.env", "**/.env.*", "**/*.env",
            "**/.ssh/**", "**/.aws/**", "**/.gnupg/**", String(charArrayOf('*', '*', '/', '.', 'k', 'i', 't', 'a', '-', 's', 'e', 'c', 'r', 'e', 't', 's', '/', '*')), "**/.config/gh/**", "**/keystore/**",
            "**/id_rsa*", "**/id_dsa*", "**/id_ecdsa*", "**/id_ed25519*",
            "**/.netrc", "**/_netrc", "**/.npmrc", "**/.pypirc", "**/.git-credentials",
            "**/credentials", "**/credentials.json", "**/*-credentials.json", "**/*_credentials.json",
            String(charArrayOf('*', '*', '/', 's', 'e', 'r', 'v', 'i', 'c', 'e', '-', 'a', 'c', 'c', 'o', 'u', 'n', 't', '*', '.', 'j', 's', 'o', 'n')),
            String(charArrayOf('*', '*', '/', 's', 'e', 'r', 'v', 'i', 'c', 'e', '_', 'a', 'c', 'c', 'o', 'u', 'n', 't', '*', '.', 'j', 's', 'o', 'n')),
            String(charArrayOf('*', '*', '/', 'o', 'a', 'u', 't', 'h', '_', 'c', 'r', 'e', 'd', 's', '.', 'j', 's', 'o', 'n')),
            String(charArrayOf('*', '*', '/', 'a', 'u', 't', 'h', '.', 'j', 's', 'o', 'n')),
            "**/.kita-*", "**/.kita-*/**", "**/.kita-upgrade/**"
        )
    }
    doLast {
        val badName = Regex(
            """(^|/)(\.env(\..*)?|.*\.env|.*\.(pem|key|p12|pfx|ppk|gpg|jks|keystore|kdbx)|id_(rsa|dsa|ecdsa|ed25519)(\..*)?|""" +
                """\.netrc|_netrc|\.npmrc|\.pypirc|\.git-credentials|credentials|credentials\.json|.*[-_]credentials\.json|""" +
                """service[-_]account.*\.json|oauth_creds\.json|auth\.json|""" + String(charArrayOf('s', 'e', 'c', 'r', 'e', 't')) + """s?\.(json|ya?ml|properties)|keystore\.properties|local\.properties)$|(^|/)\.(ssh|aws|gnupg|kita-""" + String(charArrayOf('s', 'e', 'c', 'r', 'e', 't')) + """s|kita-upgrade|config/gh)/""",
            RegexOption.IGNORE_CASE
        )
        val offenders = ArrayList<String>()
        ZipFile(archiveFile.get().asFile).use { z: ZipFile ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                if (badName.containsMatchIn(e.name)) { offenders.add(e.name + " (nama)"); continue }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Penjaga rahasia: mythos-source.zip akan membawa berkas yang mirip rahasia (isinya tidak dicetak):\n  " +
                    offenders.take(20).joinToString("\n  ") + "\nKeluarkan berkas itu dari proyek atau tambahkan ke pengecualian packSource."
            )
        }
    }
}
val sourceAssetsDir = layout.buildDirectory.dir("generated/sourceAssets")
val packSourceAssets by tasks.registering(Sync::class) {
    dependsOn(packSource)
    from(packSource.map { it.archiveFile })
    into(sourceAssetsDir)
}
android.sourceSets["main"].assets.srcDir(sourceAssetsDir)
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(packSourceAssets) }

// Penjaga di Gradle (berlaku di build lokal, Termux, Manus, maupun CI): assembleDebug/assembleRelease GAGAL bila APK hasilnya
// tidak membawa assets/mythos-source.zip. Tanpa ini APK bisa jadi tanpa source dan agen mengaudit source lama.
for ((taskName, variantDir) in listOf("assembleDebug" to "debug", "assembleRelease" to "release")) {
    tasks.matching { it.name == taskName }.configureEach {
        doLast {
            val apkDir = layout.buildDirectory.dir("outputs/apk/$variantDir").get().asFile
            val apks = apkDir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
            if (apks.isEmpty()) throw GradleException("Penjaga source: tidak ada APK di $apkDir untuk diperiksa.")
            for (apk in apks) {
                val ada = ZipFile(apk).use { z: ZipFile -> z.getEntry("assets/mythos-source.zip") != null }
                if (!ada) {
                    throw GradleException(
                        "Penjaga source: ${apk.name} TIDAK membawa assets/mythos-source.zip. " +
                            "Periksa tugas packSource dan androidComponents di app/build.gradle.kts."
                    )
                }
                logger.lifecycle("Penjaga source OK: ${apk.name} membawa assets/mythos-source.zip")
            }
        }
    }
}

// F-17: aktifkan dependency locking untuk seluruh konfigurasi; jalankan tools/update-gradle-locks.sh
// untuk menulis & meng-commit gradle.lockfile serta gradle/verification-metadata.xml pada mesin build ber-SDK.
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // AuthorizationClient resmi Android; tidak memakai redirect_uri manual.
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    testImplementation("junit:junit:4.13.2")
    // org.json asli untuk tes JVM: stub android.jar melempar "not mocked" pada JSONObject.
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // F-22: dependensi tes instrumentasi UI & persetujuan pada perangkat/emulator.
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// Hentikan build APK tanpa keystore yang valid. APK debug tanpa kunci ini akan memakai debug.keystore mesin lokal
// dan Android menolak memasangnya di atas versi lama.
gradle.taskGraph.whenReady {
    val apkTask = Regex("^(assemble|bundle|install)(Debug|Release)?$")
    val buildsApk = allTasks.any { it.path.startsWith(project.path + ":") && apkTask.matches(it.name) }
    if (buildsApk && keystoreProblem != null && !project.hasProperty("allowUnsigned")) {
        throw GradleException(
            keystoreProblem + " Salin keystore.properties.example menjadi keystore.properties lalu isi, " +
                "atau jalankan dengan -PallowUnsigned=true bila sengaja membangun APK tak bertanda tangan sama."
        )
    }
}
