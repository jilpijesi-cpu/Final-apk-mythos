# Dependensi eksternal (kotlinx-coroutines-android, play-services-auth) membawa consumer ProGuard rules sendiri di AAR.
# Aturan bawaan AGP (proguard-android-optimize.txt) menjaga Activity, Service, dan Provider di AndroidManifest otomatis.

# Nomor baris tetap ada di stack trace; petakan kembali dengan app/build/outputs/mapping/release/mapping.txt
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Nama konstanta enum disimpan ke disk (catatan perintah: Risk, ApprovalMode) dan dibaca lagi lewat valueOf().
# Jangan sampai ikut diacak antar-rilis.
-keepclassmembers enum com.build.mythos.** { *; }
