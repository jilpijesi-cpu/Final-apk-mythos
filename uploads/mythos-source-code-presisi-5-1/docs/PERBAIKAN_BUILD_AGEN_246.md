# Perbaikan: agen tidak pernah berhasil build (2.46)

Penyebab yang ditemukan lewat pembacaan kode dan simulasi (belum diuji di HP/GitHub asli):
1. Nama artifact: template proyek agen mengunggah `agentapp-debug`, skrip mengunduh `mythos-debug`. -> disamakan.
2. Git tanpa identitas (Termux baru): commit gagal diam-diam, push/repo create gagal dengan pesan menyesatkan ("src refspec HEAD does not match any"). -> identitas lokal otomatis, galat commit dilaporkan.
3. Token klasik tanpa scope `workflow`: push `.github/workflows` ditolak. -> peringatan dini + petunjuk.
4. `git push` tanpa upstream bila origin sudah ada. -> `push -u origin HEAD`.
5. Template workflow vs guard keystore Gradle 2.44. -> `-PallowUnsigned=true` bila rahasia tidak ada (template saja).

Belum dicakup: model `openrouter/free` yang lemah; membangun MYTHOS sendiri tetap butuh secret KEYSTORE_B64 + KEYSTORE_PASSWORD.
