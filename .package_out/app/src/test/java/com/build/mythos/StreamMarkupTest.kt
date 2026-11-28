package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamMarkupTest {
    @Test fun tebalBelumDitutupDisembunyikan() = assertEquals("Hasil audit", hideOpenMarkup("**Hasil audit"))

    @Test fun tebalSudahDitutupTetap() = assertEquals("**Hasil** audit", hideOpenMarkup("**Hasil** audit"))

    @Test fun kodeInlineBelumDitutupDisembunyikan() = assertEquals("buka storage.py", hideOpenMarkup("buka `storage.py"))

    @Test fun tautanSetengahJadiHanyaTeks() =
        assertEquals("Lihat storage.py", hideOpenMarkup("Lihat [storage.py](/data/data/com.term"))

    @Test fun tautanLengkapTetap() {
        val s = "Lihat [storage.py](/data/x/storage.py:23)"
        assertEquals(s, hideOpenMarkup(s))
    }

    @Test fun barisSebelumnyaTidakDisentuh() =
        assertEquals("**a\nlanjut", hideOpenMarkup("**a\n**lanjut"))

    @Test fun akhirDenganBarisBaruTidakBerubah() = assertEquals("teks\n", hideOpenMarkup("teks\n"))
}
