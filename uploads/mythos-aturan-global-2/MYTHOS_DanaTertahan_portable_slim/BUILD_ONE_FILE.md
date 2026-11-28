# MYTHOS DanaTertahan — paket portabel

Paket ini berisi source code dan konfigurasi build yang dipakai untuk MYTHOS.

- package: `com.build.mythos`
- version: `2.61` / versionCode `76`
- Google OAuth Client ID Android Debug Sandbox: `965646730787-rj8m4c5f9vn1t0necmrenjnpoh3ih8m5.apps.googleusercontent.com`
- OAuth package: `com.build.mythos`
- OAuth SHA-1 untuk build debug paket ini: `08:FD:B5:29:42:71:DE:66:7F:04:6A:FE:16:F4:CF:69:81:5F:17:F7`

## Build

Persyaratan: Linux, JDK 17+, `curl`, `unzip`, dan koneksi internet.

```bash
unzip MYTHOS_DanaTertahan_portable.zip
cd MYTHOS_DanaTertahan_portable
./build.sh testDebugUnitTest assembleDebug
```

APK berada di `app/build/outputs/apk/debug/app-debug.apk`.

`keystore.properties` dan `build-keys/mythos-debug.keystore` disertakan agar build debug di mesin lain memakai SHA-1 yang sama. Ini adalah debug key untuk testing, bukan kunci rilis produksi. Jangan gunakan untuk distribusi produksi.

`local.properties` tidak disertakan karena path Android SDK berbeda di setiap komputer; `build.sh` akan membuatnya otomatis.

Tidak ada token API, password Gmail, access token OAuth, atau kredensial konektor yang disertakan.

## Status verifikasi paket

**LULUS — diverifikasi 2026-11-22.** Paket ini telah diuji langsung dari hasil ekstraksi ZIP bersih.

- Integritas arsip ZIP: **LULUS** (`unzip -t`)
- Tes unit `testDebugUnitTest`: **LULUS**
- Build `assembleDebug`: **LULUS**
- APK berhasil dibuat dari hasil ekstraksi: **LULUS**
- SHA-256 APK hasil verifikasi sebelumnya: `506868624dabb636ecc9ce7fce3baf3574692d89e25714127eb0629fa1e631e9`

Catatan: status ini memverifikasi build debug. Keystore yang disertakan adalah debug key untuk testing, bukan signing key release produksi.
