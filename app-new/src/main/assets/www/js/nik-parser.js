function parseIndonesianNIK(nik) {
    if (!/^\d{16}$/.test(nik)) {
        return { valid: false, error: "NIK harus terdiri dari tepat 16 digit angka." };
    }

    const kodeProv = nik.substring(0, 2);
    const kodeKota = nik.substring(0, 4);
    const kodeKec = nik.substring(0, 6);
    const tglCode = nik.substring(6, 12);

    let day = parseInt(tglCode.substring(0, 2), 10);
    const month = parseInt(tglCode.substring(2, 4), 10);
    const year2Digit = parseInt(tglCode.substring(4, 6), 10);

    const isFemale = day > 40;
    if (isFemale) day -= 40;

    const today = new Date();
    const currentYear2Digit = today.getFullYear() % 100;
    const fullYear = year2Digit > currentYear2Digit ? 1900 + year2Digit : 2000 + year2Digit;

    const parsedDate = new Date(fullYear, month - 1, day);
    const isValidCalendarDate =
        month >= 1 && month <= 12 &&
        day >= 1 && day <= 31 &&
        parsedDate.getFullYear() === fullYear &&
        parsedDate.getMonth() === month - 1 &&
        parsedDate.getDate() === day;

    if (!isValidCalendarDate) {
        return {
            valid: false,
            error: `Tanggal lahir pada NIK (${String(day).padStart(2, '0')}/${String(month).padStart(2, '0')}/${fullYear}) tidak valid menurut kalender.`
        };
    }

    if (parsedDate > today) {
        return {
            valid: false,
            error: "Tanggal lahir pada NIK tidak boleh berada di masa depan."
        };
    }

    const dateStr = `${fullYear}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
    return {
        valid: true,
        kodeProv,
        kodeKota,
        kodeKec,
        provName: wilayahData.provinces[kodeProv] || null,
        cityName: wilayahData.cities[kodeKota] || null,
        kecName: wilayahData.districts[kodeKec] || null,
        dateStr,
        gender: isFemale ? "PEREMPUAN" : "LAKI-LAKI"
    };
}

function formatIndoDate(dateString) {
    if (!dateString) return "-";
    const months = [
        "JANUARI", "FEBRUARI", "MARET", "APRIL", "MEI", "JUNI",
        "JULI", "AGUSTUS", "SEPTEMBER", "OKTOBER", "NOVEMBER", "DESEMBER"
    ];
    const parts = dateString.split("-");
    if (parts.length !== 3) return dateString;
    const y = parseInt(parts[0], 10);
    const m = parseInt(parts[1], 10);
    const d = parseInt(parts[2], 10);
    if (!y || !m || !d || m < 1 || m > 12) return dateString;
    return `${String(d).padStart(2, '0')} ${months[m - 1]} ${y}`;
}
