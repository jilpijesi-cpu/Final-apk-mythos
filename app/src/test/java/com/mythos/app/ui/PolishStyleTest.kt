package com.mythos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** patch10: penghalusan tampilan (jarak baris balasan, jarak paragraf, umpan balik tekan tombol). */
class PolishStyleTest {
    private val design get() = File("src/main/java/com/mythos/app/ui/Design.kt").readText()
    private val blocks get() = File("src/main/java/com/mythos/app/ui/CopyBlocks.kt").readText()

    @Test fun balasanAiMemakaiJarakBarisLonggar() {
        val m = Regex("""fun assistantStyle\([^)]*\)\s*=\s*\n\s*bodyStyle\(color\)\.copy\([^)]*fontSize = ([\d.]+)\.sp, lineHeight = ([\d.]+)\.sp""").find(design)!!
        val ratio = m.groupValues[2].toFloat() / m.groupValues[1].toFloat()
        assertTrue("rasio jarak baris minimal 1,5 (didapat $ratio)", ratio >= 1.5f)
    }

    @Test fun jarakParagrafDanJudulDilonggarkan() {
        assertTrue(blocks.contains("is ProseBlock.Item) 5.dp else 12.dp"))
        assertTrue(blocks.contains("if (bi == 0) 0.dp else 20.dp"))
    }

    @Test fun tombolBulatMemberiUmpanBalikTekan() {
        assertTrue(design.contains("fun Modifier.pressScale("))
        assertEquals("GlyphButton dan CircleIconButton", 2, Regex("""\.pressScale\(source\)""").findAll(design).count())
        assertEquals(2, Regex("""HapticFeedbackType\.TextHandleMove""").findAll(design).count())
    }
}

/** patch11: transisi antarlayar. */
class ScreenTransitionTest {
    private val main get() = File("src/main/java/com/mythos/app/MainActivity.kt").readText()

    @Test fun pindahLayarMemakaiAnimatedContent() {
        assertTrue(main.contains("AnimatedContent("))
        assertTrue(main.contains("contentKey = { if (it == Screen.CHAT) Screen.HOME else it }"))
    }
}
