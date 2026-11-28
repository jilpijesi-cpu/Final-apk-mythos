package com.build.mythos

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test JVM untuk modul MYTHOS LeakOSINT Search Engine (LeakOsintEngine.kt).
 */
class LeakOsintEngineTest {

    @Test fun buildPayload_singleQueryAndDefaults() {
        val cfg = LeakOsintConfig(token = "123456:testToken")
        val p = LeakOsintEngine.buildPayload("example@gmail.com", cfg)
        assertEquals("123456:testToken", p.getString("token"))
        assertEquals("example@gmail.com", p.getString("request"))
        assertEquals(100, p.getInt("limit"))
        assertEquals("en", p.getString("lang"))
        assertEquals("json", p.getString("type"))
        assertFalse(p.has("bot_name"))
    }

    @Test fun buildPayload_multiLineBecomesJsonArrayAndClampsLimit() {
        val cfg = LeakOsintConfig(
            token = "123456:testToken",
            limit = 50_000,
            lang = "id",
            type = "short",
            botName = "leakOOSINTERR_bot"
        )
        val p = LeakOsintEngine.buildPayload("example@gmail.com\nElon Reeve Musk\n", cfg)
        val req = p.get("request")
        assertTrue(req is JSONArray)
        val arr = req as JSONArray
        assertEquals(2, arr.length())
        assertEquals("example@gmail.com", arr.getString(0))
        assertEquals("Elon Reeve Musk", arr.getString(1))
        assertEquals(10_000, p.getInt("limit"))
        assertEquals("id", p.getString("lang"))
        assertEquals("short", p.getString("type"))
        assertEquals("@leakOOSINTERR_bot", p.getString("bot_name"))
    }

    @Test fun estimateComplexityAndPrice_matchesDocumentationFormula() {
        assertEquals(1, LeakOsintEngine.estimateComplexity("google"))
        assertEquals(0.003, LeakOsintEngine.estimatePriceUsd(100, 1), 1e-9)
        assertEquals(5, LeakOsintEngine.estimateComplexity("Petya Ivanov"))
        assertEquals(16, LeakOsintEngine.estimateComplexity("Elon Reeve Musk"))
        assertEquals(40, LeakOsintEngine.estimateComplexity("Budi Santoso Jakarta Selatan"))
        assertEquals(1, LeakOsintEngine.estimateComplexity("\"Elon Reeve Musk\""))
        assertEquals(1, LeakOsintEngine.estimateComplexity("Budi 2024-01-15 di 12345"))
    }

    @Test fun formatStandardRecordMarkdown_outputsExactSevenFields() {
        val rec = LeakOsintRecord(
            linkedMapOf(
                "FullName" to "JEPRI PRATAMA",
                "Phone" to "+62 812-3456-7802",
                "Email" to "jepri@example.id",
                "Location" to "TANJUNG KETAPANG",
                "District" to "TOBOALI",
                "City" to "BANGKA SELATAN",
                "Region" to "TANJUNG KETAPANG",
                "State" to "Kep_Bang_Bel",
                "NIK" to "1903010101990001",
                "Gender" to "L"
            )
        )
        val formatted = LeakOsintEngine.formatStandardRecordMarkdown("PdiPerjuangan.id 2025", rec)
        val lines = formatted.lines()
        assertEquals(7, lines.size)
        assertEquals("**Sumber**: PdiPerjuangan.id 2025", lines[0])
        assertEquals("**Nama**: JEPRI PRATAMA", lines[1])
        assertEquals("**Nomor**: +62 812-3456-7802", lines[2])
        assertEquals("**Email**: jepri@example.id", lines[3])
        assertEquals("**Alamat**: TANJUNG KETAPANG, TOBOALI, BANGKA SELATAN, Kep_Bang_Bel", lines[4])
        assertEquals("**No NIK/Paspor**: 1903010101990001", lines[5])
        assertEquals("**Jenis kelamin**: Laki-laki", lines[6])
    }

    @Test fun formatStructuredRecordCopyText_includesStickersForAllSevenFields() {
        val item = LeakOsintStructuredRecord(
            indexInTarget = 1,
            sourceName = "ContohDB",
            nama = "Jepri Pratama",
            nomor = "+62 812-3456-7802",
            email = "jepri@example.id",
            alamat = "Palembang",
            nikPaspor = "1671000000000001",
            jenisKelamin = "Laki-laki"
        )
        val copied = LeakOsintEngine.formatStructuredRecordCopyText(item, "JEPRI 02")
        assertTrue(copied.contains("📌 Data #1 (JEPRI 02)"))
        assertTrue(copied.contains("📂 Sumber: ContohDB"))
        assertTrue(copied.contains("👤 Nama: Jepri Pratama"))
        assertTrue(copied.contains("📞 Nomor: +62 812-3456-7802"))
        assertTrue(copied.contains("📧 Email: jepri@example.id"))
        assertTrue(copied.contains("📍 Alamat: Palembang"))
        assertTrue(copied.contains("🪪 No NIK/Paspor: 1671000000000001"))
        assertTrue(copied.contains("⚧ Jenis kelamin: Laki-laki"))
    }

    @Test fun parseResponse_multiSourceCreatesPagesForAllSourcesWithStandardFormat() {
        val sampleJson = JSONObject()
            .put("NumOfDatabase", 2)
            .put("NumOfResults", 3)
            .put("free_requests_left", 97)
            .put("price", 0.003)
            .put(
                "List",
                JSONObject()
                    .put(
                        "SourceAlpha",
                        JSONObject()
                            .put("InfoLeak", "Deskripsi panjang yang disembunyikan di tampilan ringkas")
                            .put(
                                "Data",
                                JSONArray()
                                    .put(JSONObject().put("FullName", "Budi Santoso").put("Email", "budi@example.com"))
                                    .put(JSONObject().put("FullName", "Budi S.").put("Phone", "628123456789"))
                            )
                    )
                    .put(
                        "SourceBeta",
                        JSONObject()
                            .put("InfoLeak", "Arsip forum Beta")
                            .put(
                                "Data",
                                JSONArray()
                                    .put(JSONObject().put("Username", "budisantoso").put("City", "Palembang").put("Gender", "P"))
                            )
                    )
            ).toString()

        val outcome = LeakOsintEngine.parseResponse("Budi Santoso", sampleJson, queryId = "q1")
        assertTrue(outcome is LeakOsintOutcome.Success)
        val report = (outcome as LeakOsintOutcome.Success).report
        assertTrue(report.hasResults)
        assertEquals(2, report.sources.size)
        assertEquals(2, report.pages.size)
        assertEquals(2, report.numOfDatabase)
        assertEquals(3, report.numOfResults)
        assertEquals(97, report.freeRequestsLeft)
        assertEquals(0.003, report.price ?: 0.0, 1e-9)

        assertEquals("SourceAlpha", report.pages[0].sourceName)
        assertEquals(2, report.pages[0].structuredRecords.size)
        assertTrue(report.pages[0].markdownText.contains("**Sumber**: SourceAlpha"))
        assertTrue(report.pages[0].markdownText.contains("**Email**: budi@example.com"))
        assertFalse("Paragraf InfoLeak panjang tidak boleh mengotori tampilan ringkas", report.pages[0].markdownText.contains("Deskripsi panjang"))
        assertEquals("SourceBeta", report.pages[1].sourceName)
        assertTrue(report.pages[1].markdownText.contains("**Alamat**: Palembang"))
        assertTrue(report.pages[1].markdownText.contains("**Jenis kelamin**: Perempuan"))
    }

    @Test fun parseResponse_largeSourceSplitsIntoSubPagesWithoutLosingRecords() {
        val dataArr = JSONArray()
        for (i in 1..25) {
            dataArr.put(
                JSONObject()
                    .put("FullName", "Pengguna REC-$i")
                    .put("Email", "pengguna$i@example.org")
                    .put("Address", "Jalan Merdeka Nomor $i, Palembang, Sumatera Selatan")
            )
        }
        val json = JSONObject()
            .put("NumOfDatabase", 1)
            .put("NumOfResults", 25)
            .put(
                "List",
                JSONObject().put(
                    "MegaDump",
                    JSONObject()
                        .put("InfoLeak", "Basis data besar untuk uji pagination")
                        .put("Data", dataArr)
                )
            ).toString()

        val outcome = LeakOsintEngine.parseResponse("pengguna", json, queryId = "q2", maxPageChars = 600)
        assertTrue(outcome is LeakOsintOutcome.Success)
        val report = (outcome as LeakOsintOutcome.Success).report
        assertTrue("Sumber besar harus terbagi menjadi > 1 halaman", report.pages.size > 1)

        val combined = report.pages.joinToString("\n") { it.markdownText }
        for (i in 1..25) {
            assertTrue("Record REC-$i tidak boleh hilang", combined.contains("REC-$i"))
        }
        assertEquals(1, report.pages.first().recordStart)
        assertEquals(25, report.pages.last().recordEnd)
        assertEquals(report.pages.size, report.pages.first().totalSubPages)
    }

    @Test fun parseResponse_noResultsFoundHandledGracefully() {
        val json = JSONObject()
            .put(
                "List",
                JSONObject().put(
                    "No results found",
                    JSONObject().put("InfoLeak", "At your request, no results were found")
                )
            ).toString()

        val outcome = LeakOsintEngine.parseResponse("tidak-ada-xyz", json, queryId = "q3")
        assertTrue(outcome is LeakOsintOutcome.Success)
        val report = (outcome as LeakOsintOutcome.Success).report
        assertFalse(report.hasResults)
        assertEquals(1, report.pages.size)
        assertTrue(report.sources[0].isNoResults)
        assertTrue(report.pages[0].markdownText.contains("No results found"))
    }

    @Test fun parseResponse_errorCodeReturnsErrorOutcome() {
        val json = JSONObject().put("Error code", "Bad token").toString()
        val outcome = LeakOsintEngine.parseResponse("test", json)
        assertTrue(outcome is LeakOsintOutcome.Error)
        val err = outcome as LeakOsintOutcome.Error
        assertEquals("Bad token", err.code)
        assertTrue(err.message.contains("Token", ignoreCase = true))
    }

    @Test fun wrapPageIndex_supportsCircularNavigation() {
        assertEquals(0, LeakOsintEngine.wrapPageIndex(0, 4))
        assertEquals(3, LeakOsintEngine.wrapPageIndex(-1, 4))
        assertEquals(0, LeakOsintEngine.wrapPageIndex(4, 4))
        assertEquals(1, LeakOsintEngine.wrapPageIndex(5, 4))
        assertEquals(0, LeakOsintEngine.wrapPageIndex(99, 1))
    }

    @Test fun extractCommandQuery_parsesSlashCommands() {
        assertEquals("john@example.com", LeakOsintEngine.extractCommandQuery("/leak john@example.com"))
        assertEquals("JEPRI 02", LeakOsintEngine.extractCommandQuery("/cek JEPRI 02"))
        assertEquals("BUDI 88", LeakOsintEngine.extractCommandQuery("/cari BUDI 88"))
        assertEquals("SITI 45", LeakOsintEngine.extractCommandQuery("/data SITI 45"))
        assertEquals("Elon Musk", LeakOsintEngine.extractCommandQuery("/OSINT   Elon Musk"))
        assertEquals("628123456789", LeakOsintEngine.extractCommandQuery("/leakosint 628123456789"))
        assertNull(LeakOsintEngine.extractCommandQuery("/leak"))
        assertNull(LeakOsintEngine.extractCommandQuery("Halo apa kabar"))
    }

    @Test fun rateLimiter_enforcesThreeRequestsPerSecond() {
        var now = 1000L
        var totalSlept = 0L
        val limiter = LeakOsintRateLimiter(
            maxPerSecond = 3,
            windowMs = 1000L,
            clockMs = { now },
            sleeper = { ms -> totalSlept += ms; now += ms }
        )
        limiter.acquire()
        limiter.acquire()
        limiter.acquire()
        assertEquals(0L, totalSlept)
        limiter.acquire()
        assertEquals(1000L, totalSlept)
    }

    @Test fun search_endToEndWithMockTransportAndCache() {
        var capturedEndpoint = ""
        var capturedPayload = ""
        val mockTransport = LeakOsintTransport { endpoint, payloadJson, _, _ ->
            capturedEndpoint = endpoint
            capturedPayload = payloadJson
            LeakOsintHttpResponse(
                statusCode = 200,
                body = JSONObject()
                    .put("NumOfDatabase", 1)
                    .put("NumOfResults", 1)
                    .put(
                        "List",
                        JSONObject().put(
                            "TestDB",
                            JSONObject()
                                .put("InfoLeak", "Uji integrasi")
                                .put("Data", JSONArray().put(JSONObject().put("Email", "uji@example.com")))
                        )
                    ).toString()
            )
        }
        val cache = LeakOsintReportCache(maxEntries = 4)
        val noWaitLimiter = LeakOsintRateLimiter(clockMs = { 0L }, sleeper = {})

        val outcome = LeakOsintEngine.search(
            query = "uji@example.com",
            config = LeakOsintConfig(token = "999999:mockSecret", limit = 200, lang = "id"),
            transport = mockTransport,
            rateLimiter = noWaitLimiter,
            cache = cache
        )

        assertTrue(outcome is LeakOsintOutcome.Success)
        assertEquals(LeakOsintEngine.ENDPOINT, capturedEndpoint)
        val sent = JSONObject(capturedPayload)
        assertEquals("999999:mockSecret", sent.getString("token"))
        assertEquals("uji@example.com", sent.getString("request"))
        assertEquals(200, sent.getInt("limit"))
        assertEquals("id", sent.getString("lang"))

        val report = (outcome as LeakOsintOutcome.Success).report
        assertNotNull(cache.get(report.queryId))
    }

    @Test fun search_http501AndTokenMaskingOnNetworkError() {
        val noWaitLimiter = LeakOsintRateLimiter(clockMs = { 0L }, sleeper = {})
        val res501 = LeakOsintEngine.search(
            query = "test",
            config = LeakOsintConfig(token = "111111:secretVal"),
            transport = LeakOsintTransport { _, _, _, _ -> LeakOsintHttpResponse(501, "Not Implemented") },
            rateLimiter = noWaitLimiter
        )
        assertTrue(res501 is LeakOsintOutcome.Error)
        assertEquals("HTTP_501", (res501 as LeakOsintOutcome.Error).code)

        val resNet = LeakOsintEngine.search(
            query = "test",
            config = LeakOsintConfig(token = "111111:secretVal"),
            transport = LeakOsintTransport { _, _, _, _ ->
                throw RuntimeException("Failed for token 111111:secretVal")
            },
            rateLimiter = noWaitLimiter
        )
        assertTrue(resNet is LeakOsintOutcome.Error)
        assertFalse((resNet as LeakOsintOutcome.Error).message.contains("111111:secretVal"))
        assertTrue(resNet.message.contains("[DISEMBUNYIKAN]"))
    }

    @Test fun smartBatchParser_deduplicatesNamesAndKeepsStandardQueriesUnchanged() {
        val plan = LeakOsintEngine.parseSmartBatchInput("JEPRI 02\nJEPRI 02\nBUDI 88\nJEPRI 77\n")
        assertTrue(plan.hasSmartFilter)
        assertEquals(listOf("JEPRI", "BUDI"), plan.apiQueries)
        assertEquals(3, plan.filterRules.size)
        assertEquals("02", plan.filterRules[0].phoneSuffix)
        assertEquals("88", plan.filterRules[1].phoneSuffix)
        assertEquals("77", plan.filterRules[2].phoneSuffix)

        val plainPlan = LeakOsintEngine.parseSmartBatchInput("example@gmail.com\nBudi Santoso")
        assertFalse(plainPlan.hasSmartFilter)
        assertEquals(listOf("example@gmail.com", "Budi Santoso"), plainPlan.apiQueries)
    }

    @Test fun smartBatchSearch_filtersIndonesiaIncludingTruncatedZeroAndRejectsForeignCodes() {
        var sentPayload = ""
        val mockTransport = LeakOsintTransport { _, payloadJson, _, _ ->
            sentPayload = payloadJson
            LeakOsintHttpResponse(
                statusCode = 200,
                body = JSONObject()
                    .put("NumOfDatabase", 3)
                    .put("NumOfResults", 7)
                    .put("free_requests_left", 95)
                    .put(
                        "List",
                        JSONObject()
                            .put(
                                "Tokopedia 2020",
                                JSONObject()
                                    .put("InfoLeak", "Basis data e-commerce Indonesia")
                                    .put(
                                        "Data",
                                        JSONArray()
                                            .put(JSONObject().put("FullName", "JEPRI PRATAMA").put("Phone", "+62 812-3456-7802").put("City", "Palembang"))
                                            .put(JSONObject().put("FullName", "JEPRI TANPA NOL").put("Phone", "81345678902"))
                                            .put(JSONObject().put("FullName", "JEPRI LAIN").put("Phone", "081234567899"))
                                    )
                            )
                            .put(
                                "Facebook Global",
                                JSONObject()
                                    .put("InfoLeak", "Global social dataset")
                                    .put(
                                        "Data",
                                        JSONArray()
                                            .put(JSONObject().put("FullName", "BUDI SANTOSO").put("Phone", "0852-1111-2288"))
                                            .put(JSONObject().put("FullName", "BUDI MALAYSIA").put("Phone", "+60 12-345-6788"))
                                    )
                            )
                            .put(
                                "ForeignDB",
                                JSONObject()
                                    .put("InfoLeak", "Russian and US dataset")
                                    .put(
                                        "Data",
                                        JSONArray()
                                            .put(JSONObject().put("FullName", "JEPRI IVANOV").put("Phone", "+7 912-345-6702").put("Country", "Russia"))
                                            .put(JSONObject().put("FullName", "BUDI SMITH").put("Phone", "+1 555-010-9988").put("Country", "USA"))
                                    )
                            )
                    ).toString()
            )
        }
        val noWaitLimiter = LeakOsintRateLimiter(clockMs = { 0L }, sleeper = {})

        val outcome = LeakOsintEngine.search(
            query = "JEPRI 02\nBUDI 88",
            config = LeakOsintConfig(token = "999999:mockSecret", limit = 300, lang = "id"),
            transport = mockTransport,
            rateLimiter = noWaitLimiter
        )

        assertTrue(outcome is LeakOsintOutcome.Success)
        val sentJson = JSONObject(sentPayload)
        val reqArr = sentJson.getJSONArray("request")
        assertEquals(2, reqArr.length())
        assertEquals("JEPRI", reqArr.getString(0))
        assertEquals("BUDI", reqArr.getString(1))

        val report = (outcome as LeakOsintOutcome.Success).report
        assertTrue(report.hasResults)
        assertEquals(2, report.numOfDatabase)
        assertEquals(3, report.numOfResults)
        assertEquals(7, report.rawTotalResults)
        assertNotNull("Pengaman unfilteredReport wajib tersedia", report.unfilteredReport)
        assertEquals(7, report.unfilteredReport!!.numOfResults)

        val combined = report.pages.joinToString("\n") { it.markdownText }
        assertTrue(combined.contains("**Nama**: JEPRI PRATAMA"))
        assertTrue("Nomor terpotong nol di sumber Indonesia harus lolos", combined.contains("**Nama**: JEPRI TANPA NOL"))
        assertTrue("Nomor 08 di sumber global harus lolos", combined.contains("**Nama**: BUDI SANTOSO"))
        assertFalse("Akhiran 99 harus dibuang", combined.contains("JEPRI LAIN"))
        assertFalse("Nomor Malaysia +60 harus dibuang", combined.contains("BUDI MALAYSIA"))
        assertFalse("Data Rusia harus dibuang", combined.contains("JEPRI IVANOV"))
        assertFalse("Data USA harus dibuang", combined.contains("BUDI SMITH"))
    }

    @Test fun smartBatchSearch_preservesExactInputOrderInTargetGroups() {
        val mockTransport = LeakOsintTransport { _, _, _, _ ->
            LeakOsintHttpResponse(
                statusCode = 200,
                body = JSONObject()
                    .put("NumOfDatabase", 2)
                    .put("NumOfResults", 4)
                    .put(
                        "List",
                        JSONObject()
                            .put(
                                "Shopee ID",
                                JSONObject()
                                    .put("InfoLeak", "Marketplace Indonesia")
                                    .put(
                                        "Data",
                                        JSONArray()
                                            .put(JSONObject().put("FullName", "BUDI SANTOSO").put("Phone", "0813-1111-2288"))
                                            .put(JSONObject().put("FullName", "JEPRI HIDAYAT").put("Phone", "6281399990002"))
                                    )
                            )
                            .put(
                                "Tokopedia 2020",
                                JSONObject()
                                    .put("InfoLeak", "E-commerce Indonesia")
                                    .put(
                                        "Data",
                                        JSONArray()
                                            .put(JSONObject().put("FullName", "JEPRI PRATAMA").put("Phone", "+62 812-3456-7802"))
                                            .put(JSONObject().put("FullName", "JEPRI SAPUTRA").put("Phone", "0852-9999-0002"))
                                    )
                            )
                    ).toString()
            )
        }
        val noWaitLimiter = LeakOsintRateLimiter(clockMs = { 0L }, sleeper = {})

        val outcome = LeakOsintEngine.search(
            query = "JEPRI 02\nBUDI 88\nSITI 45",
            config = LeakOsintConfig(token = "999999:mockSecret"),
            transport = mockTransport,
            rateLimiter = noWaitLimiter
        )

        assertTrue(outcome is LeakOsintOutcome.Success)
        val report = (outcome as LeakOsintOutcome.Success).report
        assertEquals(3, report.targetGroups.size)

        // Urutan harus mengikuti input pengguna: 1. JEPRI 02 (3 data), 2. BUDI 88 (1 data), 3. SITI 45 (0 data)
        assertEquals(1, report.targetGroups[0].orderIndex)
        assertEquals("JEPRI 02", report.targetGroups[0].label)
        assertEquals(3, report.targetGroups[0].records.size)
        assertEquals(listOf(1, 2, 3), report.targetGroups[0].records.map { it.indexInTarget })

        assertEquals(2, report.targetGroups[1].orderIndex)
        assertEquals("BUDI 88", report.targetGroups[1].label)
        assertEquals(1, report.targetGroups[1].records.size)

        assertEquals(3, report.targetGroups[2].orderIndex)
        assertEquals("SITI 45", report.targetGroups[2].label)
        assertEquals(0, report.targetGroups[2].records.size)

        val allText = LeakOsintEngine.formatAllPagesMarkdown(report)
        assertTrue(allText.indexOf("🔎 1. JEPRI 02") < allText.indexOf("🔎 2. BUDI 88"))
        assertTrue(allText.indexOf("🔎 2. BUDI 88") < allText.indexOf("❌ 3. SITI 45"))
    }

    @Test fun smartBatchSearch_showsHelpfulEmptyMessageAndKeepsRawFallback() {
        val mockTransport = LeakOsintTransport { _, _, _, _ ->
            LeakOsintHttpResponse(
                statusCode = 200,
                body = JSONObject()
                    .put(
                        "List",
                        JSONObject().put(
                            "ForeignOnly",
                            JSONObject()
                                .put("InfoLeak", "USA records")
                                .put("Data", JSONArray().put(JSONObject().put("FullName", "JEPRI DOE").put("Phone", "+1 202-555-0102").put("Country", "USA")))
                        )
                    ).toString()
            )
        }
        val noWaitLimiter = LeakOsintRateLimiter(clockMs = { 0L }, sleeper = {})

        val outcome = LeakOsintEngine.search(
            query = "JEPRI 02",
            config = LeakOsintConfig(token = "999999:mockSecret"),
            transport = mockTransport,
            rateLimiter = noWaitLimiter
        )

        assertTrue(outcome is LeakOsintOutcome.Success)
        val report = (outcome as LeakOsintOutcome.Success).report
        assertFalse(report.hasResults)
        assertEquals(0, report.numOfResults)
        assertEquals(1, report.rawTotalResults)
        assertNotNull(report.unfilteredReport)
        assertTrue(report.pages[0].markdownText.contains("Tidak ditemukan data Indonesia"))
    }

    @Test fun sourceCode_containsNoHardcodedLeakOsintToken() {
        val files = listOf(
            "app/src/main/java/com/build/mythos/LeakOsintEngine.kt",
            "app/src/main/java/com/build/mythos/LeakOsintIntegration.kt",
            "app/src/main/java/com/build/mythos/SettingsDialog.kt"
        ).mapNotNull { rel -> listOf(File("../$rel"), File(rel)).firstOrNull { it.isFile } }
        assertEquals("Semua 3 berkas sumber wajib ditemukan untuk dipindai", 3, files.size)

        val tokenPattern = Regex("""\b\d{8,12}:[A-Za-z0-9]{6,20}\b""")
        for (f in files) {
            val content = f.readText()
            assertFalse("Token tidak boleh di-hardcode di ${f.name}", tokenPattern.containsMatchIn(content))
        }
    }

    @Test fun parseResponse_handlesEmptyListArrayAndNestedQueryLists() {
        val emptyArr = LeakOsintEngine.parseResponse("kosong", """{"List":[]}""")
        assertTrue(emptyArr is LeakOsintOutcome.Success)
        assertFalse((emptyArr as LeakOsintOutcome.Success).report.hasResults)

        val nestedJson = JSONObject()
            .put("JEPRI", JSONObject().put("List", JSONObject().put("DB1", JSONObject().put("Data", JSONArray().put(JSONObject().put("FullName", "JEPRI"))))))
            .put("SISITIA", JSONObject().put("List", JSONObject().put("DB2", JSONObject().put("Data", JSONArray().put(JSONObject().put("FullName", "SISITIA"))))))
            .toString()
        val nested = LeakOsintEngine.parseResponse("JEPRI\nSISITIA", nestedJson)
        assertTrue(nested is LeakOsintOutcome.Success)
        assertEquals(2, (nested as LeakOsintOutcome.Success).report.numOfResults)
    }

    @Test fun search_neverMakesMoreThanOneApiCallWhenBatchMissingList() {
        val calls = ArrayList<String>()
        val mockTransport = LeakOsintTransport { _, payloadJson, _, _ ->
            calls.add(JSONObject(payloadJson).get("request").toString())
            LeakOsintHttpResponse(200, """{"status":"unsupported_batch"}""")
        }
        val noWaitLimiter = LeakOsintRateLimiter(clockMs = { 0L }, sleeper = {})
        val outcome = LeakOsintEngine.search(
            query = "JEPRI TUNGKI ARIWIBOWO\nSISITIA ANGGRAINI",
            config = LeakOsintConfig(token = "999999:mockSecret"),
            transport = mockTransport,
            rateLimiter = noWaitLimiter
        )
        assertTrue(outcome is LeakOsintOutcome.Error)
        assertEquals("Harus maksimal 1 panggilan API agar kuota tidak terpotong berkali-kali", 1, calls.size)
        assertEquals("MISSING_LIST", (outcome as LeakOsintOutcome.Error).code)
        assertTrue(outcome.message.contains("status"))
    }

    @Test fun smartBatchParser_cleansBulletsColonsAndMultipleCommaSuffixes() {
        val input = """
            - JEREMI ARIF TJAHYADI: 6070, 3311
            - IMAM BUKHORI: 9441
            - CHAIRUL ANWAR: 5927, 5927
        """.trimIndent()
        val plan = LeakOsintEngine.parseSmartBatchInput(input)
        assertTrue(plan.hasSmartFilter)
        assertEquals(listOf("JEREMI ARIF TJAHYADI", "IMAM BUKHORI", "CHAIRUL ANWAR"), plan.apiQueries)
        assertEquals(3, plan.filterRules.size)
        assertEquals(listOf("6070", "3311"), plan.filterRules[0].effectiveSuffixes())
        assertEquals(listOf("9441"), plan.filterRules[1].effectiveSuffixes())
        assertEquals(listOf("5927"), plan.filterRules[2].effectiveSuffixes())
    }

    @Test fun isCommandPrefix_detectsActiveSlashCommandEvenBeforeQuery() {
        assertTrue(LeakOsintEngine.isCommandPrefix("/leak"))
        assertTrue(LeakOsintEngine.isCommandPrefix("/cek"))
        assertTrue(LeakOsintEngine.isCommandPrefix("/cari"))
        assertTrue(LeakOsintEngine.isCommandPrefix("/data"))
        assertTrue(LeakOsintEngine.isCommandPrefix("/LEAK "))
        assertTrue(LeakOsintEngine.isCommandPrefix("/leak\nJEPRI 02"))
        assertTrue(LeakOsintEngine.isCommandPrefix("/osint budi"))
        assertTrue(LeakOsintEngine.isCommandPrefix("/leakosint 62812"))
        assertFalse(LeakOsintEngine.isCommandPrefix("/leaks"))
        assertFalse(LeakOsintEngine.isCommandPrefix("Halo"))
        assertEquals(0..4, LeakOsintEngine.commandPrefixRange("/leak JEPRI"))
        assertNull(LeakOsintEngine.commandPrefixRange("Halo"))
    }

    @Test fun parseStoredChatReport_restoresExactTargetGroupsAndRecords() {
        val sample = """
            MYTHOS LeakOSINT · 1/2 nama cocok · 1 data

            🔎 1. FATMA ELVIA 3888 (1 cocok)

            📌 Data #1
            📂 Sumber: Dukcapil
            👤 Nama: Fatma elvia
            📞 Nomor: 6281294983888
            📧 Email: -
            📍 Alamat: Jl. camar indah 8 no.01
            🪪 No NIK/Paspor: 3172014104790008
            ⚧ Jenis kelamin: Perempuan

            ❌ 2. SITI 45 — Tidak ditemukan (0 cocok)
        """.trimIndent()
        val restored = LeakOsintEngine.parseStoredChatReport(sample)
        assertNotNull(restored)
        assertEquals(2, restored!!.targetGroups.size)
        assertEquals("FATMA ELVIA 3888", restored.targetGroups[0].label)
        assertEquals(1, restored.targetGroups[0].records.size)
        assertEquals("Dukcapil", restored.targetGroups[0].records[0].sourceName)
        assertEquals("6281294983888", restored.targetGroups[0].records[0].nomor)
        assertEquals("SITI 45", restored.targetGroups[1].label)
        assertEquals(0, restored.targetGroups[1].records.size)
        assertNull(LeakOsintEngine.parseStoredChatReport("Halo. Ada yang bisa saya bantu?"))
    }
}
