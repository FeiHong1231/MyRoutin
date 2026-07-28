package com.hss.myroutin.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.hss.myroutin.R

/**
 * 说明：系统 ProgressBar 的自适应前景 Drawable；极小进度保持完整圆角前景再裁剪，正常进度按填充宽度缩放渐变。
 *
 * @作者 huangssh
 * @版本 2.3
 */
class QuotaProgressDrawable(
    context: Context,
    isWarning: Boolean
) : Drawable() {

    /** 未填充轨道使用页面语义色，保证深色模式与浅色模式均保持层级对比。 */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.plan_usage_progress_track)
        style = Paint.Style.FILL
    }

    /** 前景颜色沿用原有正常/预警渐变，避免切换绘制策略后改变风险表达。 */
    private val progressColors = intArrayOf(
        context.getColor(if (isWarning) R.color.plan_usage_danger else R.color.plan_usage_brand_primary),
        context.getColor(
            if (isWarning) R.color.plan_usage_warning_progress_end
            else R.color.plan_usage_progress_normal_end
        )
    )

    /** 前景画笔在每次绘制时按当前分段策略配置渐变坐标。 */
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 复用轨道与正常填充区域，避免列表滚动时频繁创建绘制矩形。 */
    private val drawRect = RectF()

    /**
     * ProgressBar 会将自身进度映射为 0 到 10000 的 Drawable level；改变后重绘即可读取最新比例。
     * @param level 系统归一化后的当前进度等级
     * @return 是否需要重新绘制
     */
    override fun onLevelChange(level: Int): Boolean {
        invalidateSelf()
        return true
    }

    /**
     * 绘制完整轨道与自适应前景：填充宽度不足轨道高度时使用 Clip 策略，避免缩放后左右圆角重叠。
     * @param canvas 当前 ProgressBar 提供的绘制画布
     */
    override fun draw(canvas: Canvas) {
        val drawableBounds = bounds
        val trackWidth = drawableBounds.width().toFloat()
        val trackHeight = drawableBounds.height().toFloat()
        if (trackWidth <= 0f || trackHeight <= 0f) {
            return
        }
        val cornerRadius = trackHeight / 2f
        drawRect.set(
            drawableBounds.left.toFloat(),
            drawableBounds.top.toFloat(),
            drawableBounds.right.toFloat(),
            drawableBounds.bottom.toFloat()
        )
        canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, trackPaint)

        val filledWidth = trackWidth * level / MAX_DRAWABLE_LEVEL.toFloat()
        if (filledWidth <= 0f) {
            return
        }
        if (filledWidth < trackHeight) {
            drawClippedSmallProgress(canvas, drawableBounds, cornerRadius, filledWidth)
        } else {
            drawScaledProgress(canvas, drawableBounds, cornerRadius, filledWidth)
        }
    }

    /**
     * 极小进度先以完整轨道宽度绘制前景，再仅裁剪出真实宽度，保留左侧圆角且避免右侧圆角重叠。
     * @param canvas 当前 ProgressBar 提供的绘制画布
     * @param drawableBounds 当前 Drawable 的完整边界
     * @param cornerRadius 轨道高度的一半
     * @param filledWidth 当前真实填充宽度
     */
    private fun drawClippedSmallProgress(
        canvas: Canvas,
        drawableBounds: android.graphics.Rect,
        cornerRadius: Float,
        filledWidth: Float
    ) {
        progressPaint.shader = LinearGradient(
            drawableBounds.left.toFloat(),
            0f,
            drawableBounds.right.toFloat(),
            0f,
            progressColors,
            null,
            Shader.TileMode.CLAMP
        )
        val saveCount = canvas.save()
        canvas.clipRect(
            drawableBounds.left.toFloat(),
            drawableBounds.top.toFloat(),
            drawableBounds.left + filledWidth,
            drawableBounds.bottom.toFloat()
        )
        canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, progressPaint)
        canvas.restoreToCount(saveCount)
    }

    /**
     * 正常进度以真实填充宽度作为渐变终点，恢复前景区域内重新拉伸的圆角渐变效果。
     * @param canvas 当前 ProgressBar 提供的绘制画布
     * @param drawableBounds 当前 Drawable 的完整边界
     * @param cornerRadius 轨道高度的一半
     * @param filledWidth 当前真实填充宽度
     */
    private fun drawScaledProgress(
        canvas: Canvas,
        drawableBounds: android.graphics.Rect,
        cornerRadius: Float,
        filledWidth: Float
    ) {
        progressPaint.shader = LinearGradient(
            drawableBounds.left.toFloat(),
            0f,
            drawableBounds.left + filledWidth,
            0f,
            progressColors,
            null,
            Shader.TileMode.CLAMP
        )
        drawRect.right = drawableBounds.left + filledWidth
        canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, progressPaint)
    }

    /** 系统传入的透明度需同步到轨道与前景，确保 View alpha 动画的表现一致。 */
    override fun setAlpha(alpha: Int) {
        trackPaint.alpha = alpha
        progressPaint.alpha = alpha
    }

    /** 颜色滤镜需同步到轨道与前景，保持系统主题或父级滤镜的统一效果。 */
    override fun setColorFilter(colorFilter: ColorFilter?) {
        trackPaint.colorFilter = colorFilter
        progressPaint.colorFilter = colorFilter
    }

    /** 进度覆盖比例可变，保守返回半透明以满足 Drawable 合成约束。 */
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Android ProgressBar 将进度按该等级范围映射给 progressDrawable。 */
        private const val MAX_DRAWABLE_LEVEL = 10_000
    }
}
