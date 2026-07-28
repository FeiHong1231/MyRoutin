package com.hss.myroutin.widget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.hss.myroutin.R

/**
 * 说明：Key 用量卡片的横向圆角渐变进度条；渐变颜色对应已用比例，不依赖布局权重动态拆分 View。
 *
 * @作者 huangssh
 * @版本 2.3
 */
class RoundedGradientProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var progress = 0
    private var backgroundColor = context.getColor(R.color.plan_usage_progress_track)
    private var solidColor: Int? = null // 单一颜色（优先级高于渐变）
    private val progressPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF: RectF = RectF()
    private var cornerRadius: Float = 0f
    private var gradientShader: Shader? = null

    // 渐变节点沿用页面语义色，深色模式下会自动切换到对应的低眩光配色。
    private val gradientColors = intArrayOf(
        context.getColor(R.color.plan_usage_brand_primary),
        context.getColor(R.color.plan_usage_brand_primary),
        context.getColor(R.color.plan_usage_progress_normal_end),
        context.getColor(R.color.plan_usage_warning_progress_end),
        context.getColor(R.color.plan_usage_danger)
    )

    private val gradientPositions = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.RoundedGradientProgressBar)

        // 读取进度值
        progress = typedArray.getInt(R.styleable.RoundedGradientProgressBar_progress, 0)

        // 读取背景色
        backgroundColor = typedArray.getColor(
            R.styleable.RoundedGradientProgressBar_progress_backgroundColor,
            backgroundColor
        )

        // 读取圆角半径
        cornerRadius = typedArray.getDimension(
            R.styleable.RoundedGradientProgressBar_corner_radius,
            dpToPx(20)
        )

        // 读取单一颜色
        solidColor = typedArray.getColor(
            R.styleable.RoundedGradientProgressBar_solid_color,
            Color.TRANSPARENT
        ).takeIf { it != Color.TRANSPARENT }

        // 读取各个位置的颜色（如果提供了）
        val color0 = typedArray.getColor(
            R.styleable.RoundedGradientProgressBar_color_0,
            gradientColors[0]
        )
        val color25 = typedArray.getColor(
            R.styleable.RoundedGradientProgressBar_color_25,
            gradientColors[1]
        )
        val color50 = typedArray.getColor(
            R.styleable.RoundedGradientProgressBar_color_50,
            gradientColors[2]
        )
        val color75 = typedArray.getColor(
            R.styleable.RoundedGradientProgressBar_color_75,
            gradientColors[3]
        )
        val color100 = typedArray.getColor(
            R.styleable.RoundedGradientProgressBar_color_100,
            gradientColors[4]
        )

        // 更新颜色数组
        gradientColors[0] = color0
        gradientColors[1] = color25
        gradientColors[2] = color50
        gradientColors[3] = color75
        gradientColors[4] = color100

        typedArray.recycle()

        progressPaint.style = Paint.Style.FILL
        backgroundPaint.style = Paint.Style.FILL
        backgroundPaint.color = backgroundColor
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 创建水平渐变着色器，覆盖整个视图宽度
        if (solidColor == null) {
            gradientShader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                gradientColors,
                gradientPositions,
                Shader.TileMode.CLAMP
            )
        }
    }

    fun setProgress(progress: Int) {
        this.progress = progress.coerceIn(0, 100)
        invalidate()
    }

    override fun setBackgroundColor(color: Int) {
        backgroundColor = color
        backgroundPaint.color = color
        invalidate()
    }

    fun setCornerRadius(radius: Float) {
        cornerRadius = radius
        invalidate()
    }

    fun setSolidColor(color: Int) {
        solidColor = color
        // 清除渐变着色器
        gradientShader = null
        invalidate()
    }

    fun clearSolidColor() {
        solidColor = null
        // 重新创建渐变着色器
        if (width > 0 && height > 0) {
            gradientShader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                gradientColors,
                gradientPositions,
                Shader.TileMode.CLAMP
            )
        }
        invalidate()
    }

    fun setColorAtPosition(position: Int, color: Int) {
        when(position) {
            0 -> gradientColors[0] = color
            25 -> gradientColors[1] = color
            50 -> gradientColors[2] = color
            75 -> gradientColors[3] = color
            100 -> gradientColors[4] = color
        }
        // 重新创建着色器
        if (solidColor == null && width > 0 && height > 0) {
            gradientShader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                gradientColors,
                gradientPositions,
                Shader.TileMode.CLAMP
            )
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width
        val height = height

        // 绘制背景
        rectF.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, backgroundPaint)

        // 绘制进度条
        if (progress > 0) {
            val progressEndPoint = width * progress / 100.0f

            // 设置颜色
            if (solidColor != null) {
                // 使用单一颜色
                progressPaint.color = solidColor!!
                progressPaint.shader = null
            } else {
                // 使用渐变着色器
                progressPaint.shader = gradientShader
            }

            // 当进度小于等于1%时，绘制一个圆形来表示进度
            if (progress <= 1) {
                val radius = height / 2f
                canvas.drawCircle(radius, radius, radius, progressPaint)
            } else {
                rectF.set(0f, 0f, progressEndPoint, height.toFloat())
                canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, progressPaint)
            }
        }
    }

    private fun dpToPx(dp: Int): Float {
        return dp * resources.displayMetrics.density
    }
}
