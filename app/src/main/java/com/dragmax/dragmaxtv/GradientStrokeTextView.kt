package com.dragmax.dragmaxtv

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class GradientStrokeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {
    
    private var strokeWidth = 8f
    private var fillColors: IntArray? = null
    private var strokeColors: IntArray? = null
    
    fun setFillGradient(colors: IntArray) {
        fillColors = colors
        invalidate()
    }
    
    fun setStrokeGradient(colors: IntArray, width: Float) {
        strokeColors = colors
        strokeWidth = width
        invalidate()
    }
    
    override fun onDraw(canvas: android.graphics.Canvas) {
        val text = text.toString()
        if (text.isEmpty()) {
            super.onDraw(canvas)
            return
        }
        
        val paint = paint
        
        // Dibujar el borde (stroke) primero
        if (strokeColors != null && strokeColors!!.isNotEmpty()) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            
            val width = paint.measureText(text)
            if (width > 0) {
                val shader = LinearGradient(
                    0f, 0f, width, 0f,
                    strokeColors!!,
                    null,
                    Shader.TileMode.CLAMP
                )
                paint.shader = shader
            }
            super.onDraw(canvas)
        }
        
        // Dibujar el relleno (fill) encima
        if (fillColors != null && fillColors!!.isNotEmpty()) {
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
            
            val width = paint.measureText(text)
            if (width > 0) {
                val shader = LinearGradient(
                    0f, 0f, width, 0f,
                    fillColors!!,
                    null,
                    Shader.TileMode.CLAMP
                )
                paint.shader = shader
            }
            super.onDraw(canvas)
        } else {
            // Si no hay gradiente de fill, usar el color normal
            paint.style = Paint.Style.FILL
            paint.shader = null
            super.onDraw(canvas)
        }
    }
}






