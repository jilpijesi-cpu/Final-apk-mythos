# Perbaikan Workspace dan Perlindungan Path

## Tujuan

Menjaga repository sumber tetap terpisah dari workspace operasional agen, menyatukan lokasi kerja yang mengikuti preferensi `workdir`, dan mengurangi risiko penulisan ke direktori sumber `MYTHOS/reference` melalui path langsung maupun symlink.

## Keputusan arsitektur

- Default `workdir` tetap `~/MYTHOS` untuk kompatibilitas.
- Direktori operasional (`projects`, `attachments`, `artifacts`, `backups`, `logs`, dan `memory`) mengikuti workspace yang dipilih.
- Referensi sumber resmi tetap pada `$HOME/MYTHOS/reference` dan tidak ikut berpindah saat `workdir` diubah.
- Migrasi dari `~/kita` tidak menghapus sumber asli.

## Perubahan

1. `WorkspacePaths` menyatukan normalisasi dan resolusi path, menolak traversal `..`, awalan tilde ambigu, root `/`, area `/usr` Termux, serta workspace yang secara leksikal berada di dalam `reference`.
2. Pengaturan workspace memeriksa apakah lokasi pilihan berada di dalam repository Git. Pengguna diminta konfirmasi sebelum memilihnya; bila Termux tidak dapat memverifikasi, aplikasi meminta konfirmasi eksplisit.
3. Pembuatan struktur workspace, cadangan file (`backups/.kita_bak`), dan operasi file utama menggunakan root workspace aktif. Backup lama di `~/.kita_bak` tidak dihapus.
4. Operasi tulis aplikasi memeriksa path melalui `realpath -m` untuk mendeteksi alias symlink yang mengarah ke `reference`. Pemeriksaan gagal secara tertutup bila `realpath` tidak tersedia atau tidak dapat dijalankan.
5. Migrasi memastikan `MYTHOS/reference` bukan symlink yang mengarah ke lokasi lain sebelum menyalin sumber legacy.
6. Impor ZIP memeriksa parent dan target kanonis sebelum membuat direktori ekstraksi; kandidat nama tujuan yang merupakan symlink, termasuk dangling symlink, dilewati.
7. Penghapusan lampiran, proyek, artefak, dan cadangan memori melewati pemeriksaan path sebelum penghapusan.
8. Prompt agen dan petunjuk path diperbarui agar lokasi proyek, lampiran, log, dan artefak mengikuti workspace aktif.

## Verifikasi yang dilakukan

- `git diff --check`: lulus.
- Harness Kotlin/JVM untuk `WorkspacePaths`, `ReferencePolicy`, dan pembuatan skrip pemeriksaan path: lulus.
- Sintaks skrip migrasi diuji dengan `bash -n`.
- Migrasi disimulasikan pada HOME sementara: sumber lama tetap ada dan salinan referensi dibuat.
- Migrasi dengan `$HOME/MYTHOS` berupa symlink ditolak sebelum membuat workspace di target symlink.
- Ekstraksi ZIP diuji pada direktori normal dan pada parent symlink ke `reference`; kasus symlink ditolak sebelum membuat folder di `reference`.
- Probe repository Git diuji pada direktori di dalam repository dan di luar repository.

## Batasan yang masih berlaku

Pemeriksaan `ReferencePolicy` untuk `run_command` adalah penyaring konservatif berbasis teks/sintaks shell sederhana. Karena shell berjalan dengan UID Termux yang sama dengan pengguna, pemeriksaan ini bukan sandbox sistem operasi dan tidak dapat menjamin bahwa semua skrip, interpreter, alias symlink arbitrer, atau perintah shell kompleks tidak menulis ke `reference`. Untuk isolasi yang benar-benar kuat, eksekusi kode tidak tepercaya perlu dijalankan di sandbox/proot terpisah dengan mount/izin yang membatasi akses.

ZIP yang berisi entri symlink internal juga belum diverifikasi oleh pemindai manifest ZIP berbasis `ZipInputStream`; ekstraksi otomatis pada arsip tidak tepercaya tetap memerlukan pengujian tambahan atau ekstraktor yang menolak entri symlink.

## Status build

Build Gradle dan suite unit Android penuh belum dapat dijalankan pada lingkungan pemeriksaan ini karena Gradle Wrapper 8.9 belum tersedia secara lokal dan unduhan distribusi gagal karena DNS `services.gradle.org` tidak dapat di-resolve. Perubahan ini belum dinyatakan lulus build; jalankan `./gradlew testDebugUnitTest assembleDebug` atau gunakan workflow GitHub Actions sebelum menggabungkannya ke `main`.
