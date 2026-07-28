package com.hss.myroutin.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.hss.myroutin.R

/**
 * 说明：更新卡片的圆形下载进度，只绘制圆环底轨和进度弧，中心状态图标由布局中的 ImageView 承接。
 *
 * @作者 huangssh
 * @版本 2.3
 */
class UpdateCircleProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * 下载百分比进度，限制在 0-100，避免异常回调导致进度弧越界。
     */
    var progress: Int = MIN_PROGRESS
        set(value) {
            val safeProgress = value.coerceIn(MIN_PROGRESS, MAX_PROGRESS)
            if (field == safeProgress) {
                return
            }
            field = safeProgress
            invalidate()
        }

    /**
     * 检查更新时使用无确定进度的旋转弧，下载开始后恢复为与真实字节数对应的固定进度。
     */
    var isIndeterminate: Boolean = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            if (value) {
                startIndeterminateAnimation()
            } else {
                stopIndeterminateAnimation()
            }
            invalidate()
        }

    /**
     * 未下载进度轨道画笔使用页面语义色，确保浅色与深色模式下均有足够对比度。
     */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.plan_usage_progress_track)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = STROKE_WIDTH_DP.dp.toFloat()
    }

    /**
     * 已下载进度画笔使用品牌色，与同页的进度和操作入口保持一致。
     */
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.plan_usage_brand_primary)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = STROKE_WIDTH_DP.dp.toFloat()
    }

    /**
     * 圆环绘制区域缓存对象，避免 onDraw 中反复创建 RectF。
     */
    private val arcRect = RectF()

    /** 检查更新的旋转动画仅在 View 已附着且状态有效时运行，避免卡片隐藏后继续重绘。 */
    private var indeterminateAnimator: ValueAnimator? = null

    /** 当前无确定进度弧线的起始角度，由动画实时更新。 */
    private var indeterminateStartAngle = START_ANGLE

    /**
     * 根据当前 View 尺寸绘制完整轨道和当前进度弧，中心播放/暂停图标不在此处绘制。
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = width.coerceAtMost(height).toFloat()
        if (size <= 0f) {
            return
        }
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = size / 2f
        // 圆环按线宽的一半向内收缩，避免 stroke 被 View 边界裁剪。
        val strokeInset = progressPaint.strokeWidth / 2f
        if (radius - strokeInset <= 0f) {
            return
        }

        arcRect.set(
            centerX - radius + strokeInset,
            centerY - radius + strokeInset,
            centerX + radius - strokeInset,
            centerY + radius - strokeInset
        )
        canvas.drawArc(arcRect, START_ANGLE, FULL_SWEEP_ANGLE, false, trackPaint)
        if (isIndeterminate) {
            canvas.drawArc(
                arcRect,
                indeterminateStartAngle,
                INDETERMINATE_SWEEP_ANGLE,
                false,
                progressPaint
            )
        } else {
            canvas.drawArc(
                arcRect,
                START_ANGLE,
                progress * PROGRESS_TO_SWEEP_FACTOR,
                false,
                progressPaint
            )
        }
    }

    /** View 重新显示时恢复检查更新动画，确保页面重建后不会停在静态弧线。 */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isIndeterminate) {
            startIndeterminateAnimation()
        }
    }

    /** View 离开窗口时停止动画，避免隐藏卡片仍持续占用主线程绘制。 */
    override fun onDetachedFromWindow() {
        stopIndeterminateAnimation()
        super.onDetachedFromWindow()
    }

    /** 启动匀速旋转的加载弧；仅用于远端检查，下载过程始终显示真实百分比。 */
    private fun startIndeterminateAnimation() {
        if (!isAttachedToWindow || indeterminateAnimator?.isRunning == true) {
            return
        }
        if (indeterminateAnimator == null) {
            indeterminateAnimator = ValueAnimator.ofFloat(START_ANGLE, START_ANGLE + FULL_SWEEP_ANGLE).apply {
                duration = INDETERMINATE_ANIMATION_DURATION_MILLIS
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { animator ->
                    indeterminateStartAngle = animator.animatedValue as Float
                    invalidate()
                }
            }
        }
        indeterminateAnimator?.start()
    }

    /** 停止并复位加载弧，防止下一次检查从随机角度开始。 */
    private fun stopIndeterminateAnimation() {
        indeterminateAnimator?.cancel()
        indeterminateStartAngle = START_ANGLE
    }

    companion object {
        /**
         * 下载进度和圆弧角度的映射约束，确保 100% 精确对应一整圈。
         */
        private const val MIN_PROGRESS = 0
        private const val MAX_PROGRESS = 100
        private const val START_ANGLE = -90f
        private const val FULL_SWEEP_ANGLE = 360f
        private const val PROGRESS_TO_SWEEP_FACTOR = FULL_SWEEP_ANGLE / MAX_PROGRESS
        private const val INDETERMINATE_SWEEP_ANGLE = 90f
        private const val INDETERMINATE_ANIMATION_DURATION_MILLIS = 900L

        /**
         * 圆环线宽按更新卡片 40dp 的圆形点击区域确定，需为中心操作图标预留可辨识空间。
         */
        private const val STROKE_WIDTH_DP = 3
    }
}
