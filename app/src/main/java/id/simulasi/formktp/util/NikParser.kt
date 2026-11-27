package id.simulasi.formktp.util

import id.simulasi.formktp.data.WilayahData
import java.util.Calendar
import java.util.Locale

data class NikParseResult(
    val valid: Boolean,
    val error: String? = null,
    val kodeProv: String = "",
    val kodeKota: String = "",
    val kodeKec: String = "",
    val provName: String? = null,
    val cityName: String? = null,
    val kecName: String? = null,
    val dateIso: String = "",
    val gender: String = ""
)

object NikParser {
    private val NIK_REGEX = Regex("^\\d{16}$")
    private val INDO_MONTHS = arrayOf(
        "JANUARI", "FEBRUARI", "MARET", "APRIL", "MEI", "JUNI",
        "JULI", "AGUSTUS", "SEPTEMBER", "OKTOBER", "NOVEMBER", "DESEMBER"
    )

    fun parse(nik: String): NikParseResult {
        if (!NIK_REGEX.matches(nik)) {
            return NikParseResult(false, "NIK harus terdiri dari tepat 16 digit angka.")
        }

        val kodeProv = nik.substring(0, 2)
        val kodeKota = nik.substring(0, 4)
        val kodeKec = nik.substring(0, 6)
        val tglCode = nik.substring(6, 12)

        val provName = WilayahData.provinces[kodeProv]
        if (provName == null) {
            return NikParseResult(
                valid = false,
                error = "Kode provinsi ($kodeProv) pada 2 digit awal NIK tidak terdaftar di 38 provinsi Indonesia."
            )
        }

        var day = tglCode.substring(0, 2).toInt()
        val month = tglCode.substring(2, 4).toInt()
        val year2Digit = tglCode.substring(4, 6).toInt()

        val isFemale = day > 40
        if (isFemale) day -= 40

        val today = Calendar.getInstance()
        val currentYear2Digit = today.get(Calendar.YEAR) % 100
        val fullYear = if (year2Digit > currentYear2Digit) 1900 + year2Digit else 2000 + year2Digit

        if (month !in 1..12 || day !in 1..31) {
            return NikParseResult(
                valid = false,
                error = String.format(
                    Locale.US,
                    "Tanggal lahir pada NIK (%02d/%02d/%04d) tidak valid menurut kalender.",
                    day, month, fullYear
                )
            )
        }

        val cal = Calendar.getInstance().apply {
            isLenient = false
            set(Calendar.YEAR, fullYear)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        try {
            cal.time
        } catch (_: Exception) {
            return NikParseResult(
                valid = false,
                error = String.format(
                    Locale.US,
                    "Tanggal lahir pada NIK (%02d/%02d/%04d) tidak valid menurut kalender.",
                    day, month, fullYear
                )
            )
        }

        if (cal.after(today)) {
            return NikParseResult(false, "Tanggal lahir pada NIK tidak boleh berada di masa depan.")
        }

        val dateIso = String.format(Locale.US, "%04d-%02d-%02d", fullYear, month, day)
        val cityName = WilayahData.cities[kodeKota] ?: (if (kodeKota.length >= 3 && kodeKota[2] == '7') "Kota $kodeKota" else "Kabupaten $kodeKota")
        if (WilayahData.cities[kodeKota] == null) WilayahData.cities[kodeKota] = cityName
        
        val kecName = WilayahData.districts[kodeKec] ?: "Kecamatan $kodeKec"
        if (WilayahData.districts[kodeKec] == null) WilayahData.districts[kodeKec] = kecName

        return NikParseResult(
            valid = true,
            kodeProv = kodeProv,
            kodeKota = kodeKota,
            kodeKec = kodeKec,
            provName = provName,
            cityName = cityName,
            kecName = kecName,
            dateIso = dateIso,
            gender = if (isFemale) "PEREMPUAN" else "LAKI-LAKI"
        )
    }

    fun formatIndoDate(dateIso: String): String {
        if (dateIso.isBlank()) return "-"
        val parts = dateIso.split("-")
        if (parts.size != 3) return dateIso
        val y = parts[0].toIntOrNull() ?: return dateIso
        val m = parts[1].toIntOrNull() ?: return dateIso
        val d = parts[2].toIntOrNull() ?: return dateIso
        if (m !in 1..12 || d !in 1..31) return dateIso
        return String.format(Locale.US, "%02d %s %04d", d, INDO_MONTHS[m - 1], y)
    }

    fun formatDdMmYyyy(dateIso: String): String {
        if (dateIso.isBlank() || !dateIso.contains("-")) return "-"
        val parts = dateIso.split("-")
        if (parts.size != 3) return dateIso
        return "${parts[2]}-${parts[1]}-${parts[0]}"
    }
}
