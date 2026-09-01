package com.openclaw.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sprites = mutableMapOf<String, Bitmap>()
    private var currentSprite: Bitmap? = null
    private val dest = RectF()

    init {
        loadSprites()
        currentSprite = sprites.values.firstOrNull()
    }

    private fun loadSprites() {
        val files = try { context.assets.list("sprites") ?: emptyArray() } catch (_: Exception) { emptyArray() }
        for (file in files) {
            if (!file.endsWith(".png")) continue
            val name = file.removeSuffix(".png")
            try {
                val input = context.assets.open("sprites/$file")
                sprites[name] = BitmapFactory.decodeStream(input)
                input.close()
            } catch (_: Exception) {}
        }
    }

    fun showSprite(name: String) {
        val bmp = sprites[name] ?: return
        if (currentSprite !== bmp) {
            currentSprite = bmp
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = currentSprite ?: return
        dest.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bmp, null, dest, paint)
    }
}
