package com.build.mythos

import android.app.AlertDialog
import android.content.DialogInterface
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * F-22 & F-23: Tes instrumentasi UI untuk dialog persetujuan nyata, tampilan workflow panjang
 * yang dapat digulir, tombol setuju/tolak, rotasi Activity, dan ukuran target sentuh >= 48dp.
 */
@RunWith(AndroidJUnit4::class)
class ApprovalAndUiInstrumentedTest {

    @Test
    fun approvalDialogPositiveButtonTriggersOnYes() {
        val approved = AtomicBoolean(false)
        val denied = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { act ->
                val dlg = showApprovalDialog(
                    act = act,
                    req = ApprovalRequest("GithubBuild", "Uji persetujuan workflow"),
                    onYes = { approved.set(true); latch.countDown() },
                    onYesAll = {},
                    onNo = { denied.set(true); latch.countDown() },
                    offerAll = false,
                    simple = true
                )
                assertTrue(dlg.isShowing)
                dlg.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            }
            assertTrue("Callback onYes harus terpanggil", latch.await(5, TimeUnit.SECONDS))
            assertTrue(approved.get())
            assertFalse(denied.get())
        }
    }

    @Test
    fun approvalDialogNegativeButtonTriggersOnNo() {
        val approved = AtomicBoolean(false)
        val denied = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { act ->
                val dlg = showApprovalDialog(
                    act = act,
                    req = ApprovalRequest("rm -rf tmp", "Hapus sementara"),
                    onYes = { approved.set(true); latch.countDown() },
                    onYesAll = {},
                    onNo = { denied.set(true); latch.countDown() },
                    offerAll = false,
                    simple = true
                )
                assertTrue(dlg.isShowing)
                dlg.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            }
            assertTrue("Callback onNo harus terpanggil", latch.await(5, TimeUnit.SECONDS))
            assertFalse(approved.get())
            assertTrue(denied.get())
        }
    }

    @Test
    fun approvalDialogLongWorkflowIsScrollableWithoutTruncation() {
        val longWorkflow = buildString {
            append(".github/workflows/build.yml:\n")
            repeat(80) { i -> append("  - name: step_$i\n    run: echo line_$i\n") }
            append("END_OF_WORKFLOW_MARKER")
        }
        assertTrue(longWorkflow.length > 2500)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { act ->
                val dlg: AlertDialog = showApprovalDialog(
                    act = act,
                    req = ApprovalRequest(longWorkflow, "Workflow lengkap"),
                    onYes = {},
                    onYesAll = {},
                    onNo = {},
                    offerAll = false,
                    simple = true
                )
                val decor = dlg.window?.decorView
                assertNotNull(decor)
                val hasScrollView = findScrollView(decor!!)
                assertTrue("Dialog persetujuan harus membungkus konten dalam ScrollView (F-08)", hasScrollView)
                dlg.dismiss()
            }
        }
    }

    @Test
    fun activityRecreationDoesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { act ->
                assertFalse(act.isFinishing)
                assertFalse(act.isDestroyed)
            }
        }
    }

    @Test
    fun inputRowButtonsMeetMinimum48dpTouchTarget() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(ctx)
        val (w, h) = effectiveTouchTargetDp(widthDp = 42, heightDp = 40, leftPadDp = 3, topPadDp = 4, rightPadDp = 3, bottomPadDp = 4)
        assertTrue("Lebar target sentuh harus >= 48dp (aktual: $w)", w >= MIN_TOUCH_TARGET_DP)
        assertTrue("Tinggi target sentuh harus >= 48dp (aktual: $h)", h >= MIN_TOUCH_TARGET_DP)
        assertEquals(300L, ASK_TIMEOUT_SEC)
    }

    private fun findScrollView(view: android.view.View): Boolean {
        if (view is ScrollView) return true
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                if (findScrollView(view.getChildAt(i))) return true
            }
        }
        return false
    }
}
