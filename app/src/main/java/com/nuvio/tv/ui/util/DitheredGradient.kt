package com.nuvio.tv.ui.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

/**
 * A paint for [brush] with dithering on. Large dark-to-transparent gradients band into visible steps
 * on 8-bit (and 6-bit + FRC) TV panels; dithering hides the steps at no measurable cost.
 * Build it in drawWithCache so it is created once per size, not once per frame.
 */
fun ditheredPaint(brush: Brush, size: Size): Paint =
    Paint().also { paint ->
        brush.applyTo(size, paint, 1f)
        paint.asFrameworkPaint().isDither = true
    }

/** Draws a rectangle with a paint from [ditheredPaint]. */
fun DrawScope.drawDitheredRect(paint: Paint, topLeft: Offset, size: Size) {
    drawIntoCanvas { canvas ->
        canvas.drawRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height, paint)
    }
}
