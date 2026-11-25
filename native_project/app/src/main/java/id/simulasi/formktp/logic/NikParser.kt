package id.simulasi.formktp.logic

import java.util.*

data class NikParseResult(
    val valid: Boolean,
    val error: String? = null,
    val kodeProv: String? = null,
    val kodeKota: String? = null,
    val kodeKec: String? = null,
    val provName: String? = null,
    val cityName: String? = null,
    val kecName: String? = null,
    val dateStr: String? = null,
    val gender: String? = null
)

object NikParser {
    fun parseIndonesianNIK(nik: String): NikParseResult {
        if (!nik.matches(Regex("^\\d{16}$"))) {
            return NikParseResult(false, "NIK harus terdiri dari tepat 16 digit angka.")
        }

        val kodeProv = nik.substring(0, 2)
        val kodeKota = nik.substring(0, 4)
        val kodeKec = nik.substring(0, 6)
        val tglCode = nik.substring(6, 12)

        var day = tglCode.substring(0, 2).toInt()
        val month = tglCode.substring(2, 4).toInt()
        val year2Digit = tglCode.substring(4, 6).toInt()

        val isFemale = day > 40
        if (isFemale) day -= 40

        val today = Calendar.getInstance()
        val currentYear2Digit = today.get(Calendar.YEAR) % 100
        val fullYear = if (year2Digit > currentYear2Digit) 1900 + year2Digit else 2000 + year2Digit

        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, fullYear)
        calendar.set(Calendar.MONTH, month - 1)
        calendar.set(Calendar.DAY_OF_MONTH, day)

        val isValidCalendarDate = month in 1..12 && day in 1..31 &&
                calendar.get(Calendar.YEAR) == fullYear &&
                calendar.get(Calendar.MONTH) == month - 1 &&
                calendar.get(Calendar.DAY_OF_MONTH) == day

        if (!isValidCalendarDate) {
            return NikParseResult(
                false,
                "Tanggal lahir pada NIK (${String.format("%02d", day)}/${String.format("%02d", month)}/$fullYear) tidak valid."
            )
        }

        if (calendar.after(today)) {
            return NikParseResult(false, "Tanggal lahir pada NIK tidak boleh di masa depan.")
        }

        val dateStr = String.format("%04d-%02d-%02d", fullYear, month, day)
        return NikParseResult(
            valid = true,
            kodeProv = kodeProv,
            kodeKota = kodeKota,
            kodeKec = kodeKec,
            provName = WilayahData.provinces[kodeProv],
            cityName = WilayahData.cities[kodeKota],
            kecName = WilayahData.districts[kodeKec],
            dateStr = dateStr,
            gender = if (isFemale) "PEREMPUAN" else "LAKI-LAKI"
        )
    }

    fun formatIndoDate(dateString: String?): String {
        if (dateString.isNullOrEmpty()) return "-"
        val months = listOf(
            "JANUARI", "FEBRUARI", "MARET", "APRIL", "MEI", "JUNI",
            "JULI", "AGUSTUS", "SEPTEMBER", "OKTOBER", "NOVEMBER", "DESEMBER"
        )
        val parts = dateString.split("-")
        if (parts.size != 3) return dateString
        return try {
            val y = parts[0].toInt()
            val m = parts[1].toInt()
            val d = parts[2].toInt()
            String.format("%02d %s %d", d, months[m - 1], y)
        } catch (e: Exception) {
            dateString
        }
    }
}
