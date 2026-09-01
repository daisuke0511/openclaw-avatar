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
        currentSprite = sprites["idle"]
    }

    private fun loadSprites() {
        val names = listOf(
            "idle",
            "walk_rt_0", "walk_rt_1", "walk_rt_2",
            "walk_lt_0", "walk_lt_1", "walk_lt_2",
            "sleep_0", "sleep_1",
            "jump", "wave"
        )
        for (name in names) {
            try {
                val input = context.assets.open("sprites/$name.png")
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
