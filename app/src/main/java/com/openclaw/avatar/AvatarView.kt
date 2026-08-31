package com.openclaw.avatar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws a simple robot avatar on a transparent background.
 * Designed to be replaced later with sprite animation.
 */
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00c9a7")
        style = Paint.Style.FILL
    }

    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0d2033")
        style = Paint.Style.FILL
    }

    private val eyeWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#007a62")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4fc3f7")
        style = Paint.Style.FILL
        alpha = 200
    }

    private val legPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00a886")
        style = Paint.Style.FILL
    }

    // Reusable RectF to avoid allocation in onDraw
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        // Normalise to 100x120 virtual units
        val sx = w / 100f
        val sy = h / 120f

        // -- Legs --
        rect.set(20f * sx, 92f * sy, 40f * sx, 112f * sy)
        canvas.drawRoundRect(rect, 6f * sx, 6f * sy, legPaint)
        rect.set(60f * sx, 92f * sy, 80f * sx, 112f * sy)
        canvas.drawRoundRect(rect, 6f * sx, 6f * sy, legPaint)

        // -- Body --
        rect.set(14f * sx, 48f * sy, 86f * sx, 96f * sy)
        canvas.drawRoundRect(rect, 10f * sx, 10f * sy, bodyPaint)
        canvas.drawRoundRect(rect, 10f * sx, 10f * sy, outlinePaint)

        // Body screen
        rect.set(26f * sx, 56f * sy, 74f * sx, 88f * sy)
        canvas.drawRoundRect(rect, 5f * sx, 5f * sy, darkPaint)
        rect.set(30f * sx, 60f * sy, 70f * sx, 84f * sy)
        canvas.drawRoundRect(rect, 4f * sx, 4f * sy, screenPaint)

        // -- Head --
        rect.set(18f * sx, 8f * sy, 82f * sx, 56f * sy)
        canvas.drawRoundRect(rect, 14f * sx, 14f * sy, bodyPaint)
        canvas.drawRoundRect(rect, 14f * sx, 14f * sy, outlinePaint)

        // Eyes (white)
        canvas.drawCircle(36f * sx, 28f * sy, 8f * sx, eyeWhitePaint)
        canvas.drawCircle(64f * sx, 28f * sy, 8f * sx, eyeWhitePaint)
        // Pupils
        canvas.drawCircle(37f * sx, 29f * sy, 4f * sx, darkPaint)
        canvas.drawCircle(65f * sx, 29f * sy, 4f * sx, darkPaint)

        // Mouth / grill
        rect.set(34f * sx, 42f * sy, 66f * sx, 48f * sy)
        canvas.drawRoundRect(rect, 3f * sx, 3f * sy, darkPaint)
    }
}
