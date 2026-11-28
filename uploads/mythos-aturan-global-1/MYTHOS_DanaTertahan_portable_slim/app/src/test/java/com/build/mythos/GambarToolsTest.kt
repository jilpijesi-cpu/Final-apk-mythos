package com.build.mythos

import com.build.mythos.cc.BuatGambar
import com.build.mythos.cc.EditGambar
import com.build.mythos.cc.ToolRegistry
import com.build.mythos.cc.ToolSchemas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GambarToolsTest {
    @Test fun ccApiEngineSchemasAndTitlesCoverImageTools() {
        val reg = ToolRegistry.default(
            withBuild = false,
            extra = listOf(
                BuatGambar { _, _, _, _ -> true to "ok" },
                EditGambar { _, _, _, _ -> true to "ok" }
            )
        )
        val specs = ToolSchemas.specs(reg).associateBy { it.name }
        assertTrue("buat_gambar harus punya skema native", "buat_gambar" in specs)
        assertTrue("edit_gambar harus punya skema native", "edit_gambar" in specs)
        assertTrue(specs.getValue("buat_gambar").schema.getJSONObject("properties").has("prompt"))
        assertTrue(specs.getValue("edit_gambar").schema.getJSONObject("properties").has("instruksi"))

        assertTrue(ccToolTitle("buat_gambar", "", "buat_gambar kucing").contains("gambar", ignoreCase = true))
        assertTrue(ccToolTitle("edit_gambar", "foto.png", "edit_gambar foto.png").contains("foto.png"))
    }

    @Test fun promptValidationChecksLengthOnly() {
        assertNull(GambarTools.checkPrompt("Kucing oranye duduk di jendela saat hujan sore"))
        assertNull(GambarTools.checkPrompt("ok"))
        assertNull(GambarTools.checkPrompt("x".repeat(GambarTools.MAX_PROMPT_LEN)))
        assertNotNull(GambarTools.checkPrompt(""))
        assertNotNull(GambarTools.checkPrompt("a"))
        assertNotNull(GambarTools.checkPrompt("x".repeat(GambarTools.MAX_PROMPT_LEN + 1)))
    }

    @Test fun ratioValidationAcceptsBlankAndSupportedOnly() {
        assertNull(GambarTools.checkRatio(""))
        assertNull(GambarTools.checkRatio("16:9"))
        assertNull(GambarTools.checkRatio("1:1"))
        assertNotNull(GambarTools.checkRatio("7:5"))
    }

    @Test fun pathValidationChecksExtensionExistenceAndSize() {
        assertNull(GambarTools.checkInputPath("attachments/foto.jpg", exists = true, readable = true, sizeBytes = 1024L))
        assertNotNull(GambarTools.checkInputPath("", exists = true, readable = true, sizeBytes = 1024L))
        assertNotNull(GambarTools.checkInputPath("notes.txt", exists = true, readable = true, sizeBytes = 1024L))
        assertNotNull(GambarTools.checkInputPath("foto.png", exists = false, readable = false, sizeBytes = 0L))
        assertNotNull(GambarTools.checkInputPath("foto.png", exists = true, readable = true, sizeBytes = 0L))
        assertNotNull(GambarTools.checkInputPath("foto.png", exists = true, readable = true, sizeBytes = GambarTools.MAX_INPUT_BYTES + 1L))
    }

    @Test fun inSampleSizeScalesDownLargeDimensions() {
        assertEquals(1, GambarTools.computeInSampleSize(800, 600, 1600))
        assertEquals(2, GambarTools.computeInSampleSize(3200, 1800, 1600))
        assertEquals(4, GambarTools.computeInSampleSize(4096, 4096, 1600))
        assertEquals(1, GambarTools.computeInSampleSize(0, 0, 1600))
    }

    @Test fun requestBuildersIncludeExpectedFields() {
        val gen = GambarTools.buildGenerateRequest("Pemandangan gunung", "16:9")
        val genCfg = gen.getJSONObject("generationConfig")
        assertEquals("16:9", genCfg.getJSONObject("imageConfig").getString("aspectRatio"))
        assertEquals(2, genCfg.getJSONArray("responseModalities").length())

        val edit = GambarTools.buildEditRequest("Ubah langit jadi senja", "image/jpeg", "YWJj")
        val parts = edit.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts.length())
        assertEquals("Ubah langit jadi senja", parts.getJSONObject(0).getString("text"))
        assertEquals("image/jpeg", parts.getJSONObject(1).getJSONObject("inlineData").getString("mimeType"))
        assertEquals("YWJj", parts.getJSONObject(1).getJSONObject("inlineData").getString("data"))
    }

    @Test fun parseResponseHandlesInlineDataThoughtSafetyAndHttp404403() {
        val okJson = """
            {"candidates":[{"finishReason":"STOP","content":{"parts":[
              {"thought":true,"inlineData":{"mimeType":"image/png","data":"VEhPVUdIVA=="}},
              {"text":"Ini gambarnya"},
              {"inlineData":{"mimeType":"image/png","data":"iVBORw0KGgo="}}
            ]}}]}
        """.trimIndent()
        val ok = GambarTools.parseImageResponse(okJson)
        assertTrue(ok is ImageApiResult.Ok)
        ok as ImageApiResult.Ok
        assertEquals("image/png", ok.mimeType)
        assertEquals("iVBORw0KGgo=", ok.base64)

        val blockedJson = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        val blocked = GambarTools.parseImageResponse(blockedJson)
        assertTrue(blocked is ImageApiResult.Fail)
        assertTrue((blocked as ImageApiResult.Fail).message.contains("SAFETY"))

        val finishBlocked = """{"candidates":[{"finishReason":"IMAGE_SAFETY","content":{"parts":[]}}]}"""
        val fb = GambarTools.parseImageResponse(finishBlocked)
        assertTrue(fb is ImageApiResult.Fail)
        assertTrue((fb as ImageApiResult.Fail).message.contains("IMAGE_SAFETY"))

        val notFound = """{"error":{"code":404,"status":"NOT_FOUND","message":"model not found"}}"""
        val nf = GambarTools.parseImageResponse(notFound, "gemini-3.1-flash-image")
        assertTrue(nf is ImageApiResult.Fail)
        val nfMsg = (nf as ImageApiResult.Fail).message
        assertTrue(nfMsg.contains("gemini-3.1-flash-image"))
        assertTrue(nfMsg.contains("IMAGE_MODEL"))

        val forbidden = """{"error":{"code":403,"status":"PERMISSION_DENIED","message":"permission denied"}}"""
        val f403 = GambarTools.parseImageResponse(forbidden, "gemini-3.1-flash-image")
        assertTrue(f403 is ImageApiResult.Fail)
        val f403Msg = (f403 as ImageApiResult.Fail).message
        assertTrue(f403Msg.contains("gemini-3.1-flash-image"))
        assertTrue(f403Msg.contains("IMAGE_MODEL"))
    }

    @Test
    fun deapiSizeMappingAndRequests() {
        assertEquals("1024x1024", GambarTools.deapiSizeForRatio(""))
        assertEquals("1024x1024", GambarTools.deapiSizeForRatio("1:1"))
        assertEquals("1536x1024", GambarTools.deapiSizeForRatio("16:9"))
        assertEquals("1024x1536", GambarTools.deapiSizeForRatio("9:16"))

        val gen = GambarTools.buildDeapiGenerateRequest("gunung bersalju", "16:9")
        assertEquals(GambarTools.DEAPI_GENERATE_MODEL, gen.getString("model"))
        assertEquals("1536x1024", gen.getString("size"))
        assertEquals("b64_json", gen.getString("response_format"))

        val edit = GambarTools.buildDeapiEditRequest("ubah langit jadi senja", "image/png", "AAAA")
        assertEquals(GambarTools.DEAPI_EDIT_MODEL, edit.getString("model"))
        assertEquals("data:image/png;base64,AAAA", edit.getJSONArray("images").getJSONObject(0).getString("image_url"))
    }

    @Test
    fun deapiParseResponseOkAndError() {
        val ok = GambarTools.parseDeapiImageResponse("{\"data\":[{\"b64_json\":\"iVBORw0KGgo=\"}]}")
        assertTrue(ok is ImageApiResult.Ok)
        assertEquals("iVBORw0KGgo=", (ok as ImageApiResult.Ok).base64)

        val err = GambarTools.parseDeapiImageResponse("{\"error\":{\"message\":\"Insufficient balance.\",\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}")
        assertTrue(err is ImageApiResult.Fail)
        assertTrue((err as ImageApiResult.Fail).message.contains("habis", ignoreCase = true))
    }
}
