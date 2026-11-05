package com.chrisb588.easlie

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.chrisb588.easlie.canvas.BoardItem
import com.chrisb588.easlie.canvas.inStackingOrder
import com.chrisb588.easlie.images.readImageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

/** A bounded, disposable thumbnail. Never opens another board or changes either viewport. */
internal suspend fun renderBoardPreview(storage: BoardStorage, items: List<BoardItem>): ImageBitmap? {
    if (items.isEmpty()) return null
    val points = items.flatMap { item -> listOf(item.corner(-1f, -1f), item.corner(1f, -1f), item.corner(1f, 1f), item.corner(-1f, 1f)) }
    val bounds = RectF(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    val scale = 112f / maxOf(bounds.width(), bounds.height()).coerceAtLeast(1f)
    val result = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(result)
        canvas.translate(64f, 64f)
        canvas.scale(scale, scale)
        canvas.translate(-bounds.centerX(), -bounds.centerY())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (item in items.inStackingOrder()) {
            currentCoroutineContext().ensureActive()
            try {
                val source = readImageSource(storage.asset(item.assetId))
                var sample = 1
                while (source.edge / sample > 128) sample *= 2
                val bitmap = source.decode(sample)
                try {
                    canvas.save()
                    canvas.rotate(item.rotationDegrees, item.center.x, item.center.y)
                    canvas.drawBitmap(bitmap, null, RectF(item.center.x - item.width / 2, item.center.y - item.height / 2,
                        item.center.x + item.width / 2, item.center.y + item.height / 2), paint)
                    canvas.restore()
                } finally { bitmap.recycle() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A missing preview asset must not prevent opening the board's recovery UI.
            }
        }
        return result.asImageBitmap()
    } catch (failure: Exception) {
        result.recycle()
        throw failure
    }
}
