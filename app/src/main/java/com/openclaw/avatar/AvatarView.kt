package com.openclaw.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var characterBitmap: Bitmap? = null
    private var flipped = false
    private val flipMatrix = Matrix()

    fun setFlipped(flip: Boolean) {
        if (flipped != flip) {
            flipped = flip
            invalidate()
        }
    }

    init {
        loadCharacter()
    }

    private fun loadCharacter() {
        try {
            val input = context.assets.open("character.png")
            val full = BitmapFactory.decodeStream(input)
            input.close()
            // Crop to main standing character (far-left of sprite sheet)
            // Sprite sheet is 1536x1024; main character is roughly left 15% x top 60%
            val cropW = (full.width * 0.15f).toInt()
            val cropH = (full.height * 0.60f).toInt()
            val cropped = Bitmap.createBitmap(full, 0, 0, cropW, cropH)
            full.recycle()
            characterBitmap = removeWhiteBackground(cropped)
        } catch (_: Exception) {}
    }

    private fun removeWhiteBackground(src: Bitmap, threshold: Int = 30): Bitmap {
        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(result.width * result.height)
        result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            if (r >= 255 - threshold && g >= 255 - threshold && b >= 255 - threshold) {
                pixels[i] = Color.TRANSPARENT
            }
        }
        result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        src.recycle()
        return result
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = characterBitmap ?: return
        if (flipped) {
            flipMatrix.setScale(-1f, 1f, width / 2f, height / 2f)
            canvas.save()
            canvas.concat(flipMatrix)
        }
        canvas.drawBitmap(bmp, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bitmapPaint)
        if (flipped) canvas.restore()
    }
}
