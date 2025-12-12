package com.alezzgo.lunalab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun CameraCircleOverlay(
    modifier: Modifier = Modifier,
    scrimColor: Color = Color(0xB0000000),
    strokeColor: Color = Color.White,
) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2.2f
        val center = Offset(x = size.width / 2f, y = size.height / 2f)

        val holePath = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addOval(
                Rect(
                    left = center.x - radius,
                    top = center.y - radius,
                    right = center.x + radius,
                    bottom = center.y + radius
                )
            )
        }

        drawPath(holePath, color = scrimColor)
        drawCircle(
            color = strokeColor,
            radius = radius,
            center = center,
            style = Stroke(width = 4.dp.toPx())
        )
    }
}


