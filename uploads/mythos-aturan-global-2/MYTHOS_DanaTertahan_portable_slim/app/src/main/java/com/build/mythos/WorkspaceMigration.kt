package com.build.mythos

/** Migrasi satu kali dari workspace lama ~/kita ke reference read-only tanpa menghapus sumber asli. */
internal object WorkspaceMigration {
    const val DONE = "mythos_legacy_migration_v3_readonly_done"

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun command(workspaceRoot: String): String = """
old="${'$'}HOME/kita"; reference="${'$'}HOME/MYTHOS/reference"; new="${'$'}reference/automatic-couscous-2"; workspace=${shq(workspaceRoot)}
# Reference harus tetap menjadi direktori nyata di bawah HOME/MYTHOS, bukan symlink ke lokasi lain.
command -v realpath >/dev/null 2>&1 || { echo MYTHOS_MIGRATION=fail_realpath; exit 93; }
home_real=${'$'}(realpath -m -- "${'$'}HOME") || { echo MYTHOS_MIGRATION=fail_reference; exit 93; }
reference_real_expected="${'$'}home_real/MYTHOS/reference"
reference_real=${'$'}(realpath -m -- "${'$'}reference") || { echo MYTHOS_MIGRATION=fail_reference; exit 93; }
[ "${'$'}reference_real" = "${'$'}reference_real_expected" ] || { echo MYTHOS_MIGRATION=fail_reference_symlink; exit 93; }
[ ! -L "${'$'}HOME/MYTHOS" ] && [ ! -L "${'$'}reference" ] || { echo MYTHOS_MIGRATION=fail_reference_symlink; exit 93; }
mkdir -p "${'$'}workspace/projects/aktif" "${'$'}workspace/projects/arsip" "${'$'}workspace/artifacts" "${'$'}workspace/attachments" "${'$'}workspace/backups" "${'$'}workspace/logs" "${'$'}workspace/memory"
mkdir -p "${'$'}reference" || { echo MYTHOS_MIGRATION=fail_reference; exit 93; }
new_parent_real=${'$'}(realpath -m -- "${'$'}(dirname "${'$'}new")") || { echo MYTHOS_MIGRATION=fail_reference; exit 93; }
[ "${'$'}reference_real" = "${'$'}new_parent_real" ] || { echo MYTHOS_MIGRATION=fail_reference; exit 93; }
[ ! -L "${'$'}new" ] || { echo MYTHOS_MIGRATION=fail_symlink; exit 93; }
if [ ! -d "${'$'}old" ]; then echo MYTHOS_MIGRATION=none; exit 0; fi
mkdir -p "${'$'}new"
# Allowlist ini mengikuti automatic-couscous-github-build.zip: proyek Android AgentApp saja.
for item in \
  .gitignore .github app gradle tools \
  build.gradle.kts settings.gradle.kts gradle.properties gradlew gradlew.bat \
  keystore.properties.example \
  STRUKTUR.md DOKUMENTASI.md PERBAIKAN_BUILD_AGEN.md PERBAIKAN_LOGIKA_AGEN.md \
  PERBAIKAN_KONEKSI.md PERUBAHAN_TAMPILAN.md AUDIT_KODE_DAN_UNIT_TEST.md \
  AUDIT_KEDUA_PERBAIKAN.md AUDIT_LOGIKA_AGEN.md SANDBOX.md SELF_UPGRADE.md \
  build-termux.sh; do
  if [ -e "${'$'}old/${'$'}item" ]; then
    parent=${'$'}(dirname "${'$'}item")
    mkdir -p "${'$'}new/${'$'}parent"
    cp -a "${'$'}old/${'$'}item" "${'$'}new/${'$'}item"
  fi
done
chmod -R a-w "${'$'}new"
printf '%s\n' MYTHOS_MIGRATION=ok MYTHOS_SOURCE="${'$'}new" MYTHOS_POLICY=android-source-readonly
""".trimIndent()
}

internal fun MainActivity.startLegacyWorkspaceMigration() {
    if (prefs.getBoolean(WorkspaceMigration.DONE, false)) {
        bg { try { ensureWorkspaceLayout() } catch (ignored: Exception) { logQuiet("WorkspaceMigration.kt", ignored) } }
        return
    }
    bg {
        val workspaceGuard = referencePathsGuard(
            listOf("projects/aktif", "projects/arsip", "artifacts", "attachments", "backups", "logs", "memory")
                .map { "${workspaceBase()}/$it" }
        )
        if (workspaceGuard != null) {
            logQuiet("WorkspaceMigration.kt", IllegalStateException("Migrasi dibatalkan: $workspaceGuard"))
            return@bg
        }
        val result = try { termux(WorkspaceMigration.command(workspaceBase()), limit = 4000, timeoutSec = 120, useWorkdir = false, killable = false) }
        catch (ignored: Exception) { "exit=1 migrasi gagal" }
        if (result.startsWith("exit=0") && (result.contains("MYTHOS_MIGRATION=ok") || result.contains("MYTHOS_MIGRATION=none"))) {
            prefs.edit().putBoolean(WorkspaceMigration.DONE, true).apply()
        }
    }
}
