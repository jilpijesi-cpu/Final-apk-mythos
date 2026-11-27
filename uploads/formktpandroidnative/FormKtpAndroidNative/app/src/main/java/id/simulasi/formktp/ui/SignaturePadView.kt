package id.simulasi.formktp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SignaturePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val currentPath = Path()
    private val paths = mutableListOf<Path>()
    private var loadedBitmap: Bitmap? = null
    private var hasDrawing = false

    var onSignatureChanged: ((Bitmap?) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        loadedBitmap?.let { bmp ->
            val dst = Rect(0, 0, width, height)
            canvas.drawBitmap(bmp, null, dst, null)
        }
        paths.forEach { canvas.drawPath(it, strokePaint) }
        canvas.drawPath(currentPath, strokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                currentPath.reset()
                currentPath.moveTo(x, y)
                currentPath.lineTo(x + 0.1f, y + 0.1f)
                hasDrawing = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath.lineTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                paths.add(Path(currentPath))
                currentPath.reset()
                invalidate()
                onSignatureChanged?.invoke(exportBitmap())
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun setSignatureBitmap(bitmap: Bitmap?) {
        paths.clear()
        currentPath.reset()
        loadedBitmap = bitmap
        hasDrawing = bitmap != null
        invalidate()
    }

    fun clear() {
        paths.clear()
        currentPath.reset()
        loadedBitmap = null
        hasDrawing = false
        invalidate()
        onSignatureChanged?.invoke(null)
    }

    fun hasSignature(): Boolean = hasDrawing

    fun exportBitmap(): Bitmap? {
        if (!hasDrawing) return null
        val targetW = if (width > 0) width else 440
        val targetH = if (height > 0) height else 160
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.TRANSPARENT)
        loadedBitmap?.let { bmp ->
            c.drawBitmap(bmp, null, Rect(0, 0, targetW, targetH), null)
        }
        paths.forEach { c.drawPath(it, strokePaint) }
        c.drawPath(currentPath, strokePaint)
        return out
    }
}
