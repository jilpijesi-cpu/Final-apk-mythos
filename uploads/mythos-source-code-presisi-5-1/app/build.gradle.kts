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

// applicationId dapat diganti per build: ./gradlew assembleDebug -PappId=com.mythos.app. Harus berupa nama package yang valid.
val appId: String = (project.findProperty("appId") as String?)?.trim().takeUnless { it.isNullOrEmpty() } ?: "com.build.mythos"
require(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$").matches(appId)) { "appId tidak valid: '$appId' (contoh: com.build.mythos)" }
logger.lifecycle("MYTHOS applicationId = $appId (ganti dengan -PappId=... bila harus memperbarui instalasi package lain)")

android {
    namespace = "com.build.mythos"
    compileSdk = 34

    defaultConfig {
        // applicationId menentukan apakah APK ini MEMPERBARUI instalasi lama atau dipasang sebagai aplikasi terpisah (F-07).
        // Bawaan com.build.mythos. Untuk memperbarui instalasi dengan package lain, bangun dengan -PappId=<package-target>
        // dan tandatangani dengan sertifikat yang sama. Package/versionCode/sertifikat divalidasi lagi oleh ApkVerify saat pasang.
        applicationId = appId
        minSdk = 26
        targetSdk = 34
        versionCode = 61
        versionName = "2.46"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
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
// Saat aplikasi pertama kali jalan di versi baru, source itu diekstrak ke ~/MYTHOS/projects/aktif/mythos-<versi>
// di Termux, jadi agen selalu punya source yang SAMA dengan aplikasi terpasang (tanpa impor manual).
val packSource by tasks.registering(Zip::class) {
    archiveFileName.set("mythos-source.zip")
    // Folder keluaran (destinationDirectory) DIATUR OLEH AGP lewat addGeneratedSourceDirectory di bawah; jangan diisi di sini.
    // SEMUA berkas proyek, kecuali hasil build dan rahasia. Pengecualian memakai jalur persis dari akar proyek
    // (jangan pakai pola "**/build/**": itu juga membuang paket com/build/mythos di dalam source).
    from(rootProject.projectDir) {
        exclude(
            "build/**", "app/build/**", ".gradle/**", ".git/**", ".idea/**", "**/.DS_Store",
            "keystore.properties", "local.properties", "**/*.jks", "**/*.keystore", "**/*.p12", "**/*.apk", "**/*.aab",
            // Rahasia lain (F-03): kunci, token, dan kredensial TIDAK boleh ikut ke APK yang dibagikan ke semua pengguna.
            "**/*.pfx", "**/*.pem", "**/*.key", "**/*.ppk", "**/*.gpg",
            "**/.env", "**/.env.*", "**/*.env",
            "**/.ssh/**", "**/.aws/**", "**/.gnupg/**", "**/.kita-secrets/**", "**/.config/gh/**", "**/keystore/**",
            "**/id_rsa*", "**/id_dsa*", "**/id_ecdsa*", "**/id_ed25519*",
            "**/.netrc", "**/_netrc", "**/.npmrc", "**/.pypirc", "**/.git-credentials",
            "**/credentials", "**/credentials.json", "**/*-credentials.json", "**/*_credentials.json",
            "**/service-account*.json", "**/service_account*.json", "**/oauth_creds.json", "**/.kita-*"
        )
    }
    // Pagar kedua (fail-closed): setelah arsip jadi, periksa NAMA dan ISI semua entri. Bila ada yang mirip rahasia, build GAGAL
    // (bukan hanya berkas yang terlewat dari daftar pengecualian di atas). Berkas teks > 2 MB dan biner dilewati untuk isi.
    doLast {
        val badName = Regex(
            """(^|/)(\.env(\..*)?|.*\.env|.*\.(pem|key|p12|pfx|ppk|gpg|jks|keystore)|id_(rsa|dsa|ecdsa|ed25519)(\..*)?|""" +
                """\.netrc|_netrc|\.npmrc|\.pypirc|\.git-credentials|credentials|credentials\.json|.*[-_]credentials\.json|""" +
                """service[-_]account.*\.json|oauth_creds\.json|keystore\.properties|local\.properties)$|(^|/)\.(ssh|aws|gnupg|kita-secrets)/"""
        )
        val badContent = Regex(
            "-----BEGIN ([A-Z0-9]+ )*PRIVATE KEY-----|ghp_[A-Za-z0-9]{36}|gho_[A-Za-z0-9]{36}|ghs_[A-Za-z0-9]{36}|" +
                "github_pat_[A-Za-z0-9_]{50,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|xox[baprs]-[A-Za-z0-9-]{10,}|" +
                "sk-ant-[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9]{40,}"
        )
        val offenders = ArrayList<String>()
        ZipFile(archiveFile.get().asFile).use { z: ZipFile ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                if (badName.containsMatchIn(e.name)) { offenders.add(e.name + " (nama)"); continue }
                if (e.size in 1L..2_000_000L) {
                    val text = z.getInputStream(e).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
                    if (badContent.containsMatchIn(text)) offenders.add(e.name + " (isi)")
                }
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
// Salin arsip sebagai satu file ke direktori asset generated agar nama assets/mythos-source.zip tetap tepat.
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

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    // org.json asli untuk tes JVM: stub android.jar melempar "not mocked" pada JSONObject.
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
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
