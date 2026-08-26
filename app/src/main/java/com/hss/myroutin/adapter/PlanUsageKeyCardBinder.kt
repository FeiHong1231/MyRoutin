package com.hss.myroutin.adapter

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.hss.myroutin.BuildConfig
import com.hss.myroutin.R
import com.hss.myroutin.databinding.ItemPlanUsageKeyBinding
import com.hss.myroutin.logic.PlanUsageFormatter
import com.hss.myroutin.logic.PlanUsageQuotaCalculator
import com.hss.myroutin.model.PlanUsageQueryStatus
import com.hss.myroutin.model.PlanUsageSnapshot
import com.hss.myroutin.model.SavedPlanKey
import com.hss.myroutin.widget.QuotaProgressDrawable
import kotlin.math.roundToInt

/**
 * 说明：订阅 Key 卡片展示绑定器，集中处理卡片状态、额度、倍率样式和进度日志，不参与列表生命周期。
 *
 * @param onTogglePlanKey 展开或收起当前 Key 的交互回调
 * @param onRefreshPlanKey 刷新当前 Key 的交互回调
 * @param onManagePlanKey 打开当前 Key 管理入口的交互回调
 * @param onCopyPlanKey 将当前完整 Key 写入系统剪贴板的交互回调
 * @作者 huangssh
 * @版本 2.3
 */
internal class PlanUsageKeyCardBinder(
    private val onTogglePlanKey: (String) -> Unit,
    private val onRefreshPlanKey: (String) -> Unit,
    private val onManagePlanKey: (View, SavedPlanKey) -> Unit,
    private val onCopyPlanKey: (SavedPlanKey) -> Unit
) {

    /**
     * 为复用的 XML 卡片写入当前 Key 数据，所有可变区块只切换已有节点的内容和可见性。
     * @param cardBinding 当前 ViewHolder 对应的卡片 Binding
     * @param planKey 当前 Key 的持久化数据和缓存
     * @param isRefreshing 当前 Key 是否正在请求
     * @param latestError 当前页面会话内最近一次刷新错误
     */
    fun bind(
        cardBinding: ItemPlanUsageKeyBinding,
        planKey: SavedPlanKey,
        isRefreshing: Boolean,
        latestError: String?
    ) = with(cardBinding) {
        tvKeyName.text = planKey.name
        tvMaskedKey.text = PlanUsageFormatter.maskKey(planKey.apiKey)
        tvKeyRefreshing.visibility = if (isRefreshing) View.VISIBLE else View.INVISIBLE
        tvRefresh.text = root.context.getString(R.string.action_refresh)
        bindStatusMessage(tvStatusMessage, planKey, latestError)
        llKeyDetails.visibility = if (planKey.isExpanded) View.VISIBLE else View.GONE
        resetCollapsedQuotaVisibility(cardBinding)
        llKeyHeader.setOnClickListener { onTogglePlanKey(planKey.id) }
        tvRefresh.setOnClickListener { onRefreshPlanKey(planKey.id) }
        tvManage.setOnClickListener { onManagePlanKey(tvManage, planKey) }
        btnCopyKey.setOnClickListener { onCopyPlanKey(planKey) }
        if (planKey.isExpanded) {
            bindPlanKeyDetails(cardBinding, planKey)
        } else {
            bindCollapsedQuotaProgress(cardBinding, planKey)
        }
    }

    /**
     * 收起态按接口的短窗口字段展示五小时额度，再展示周额度；缺少对应数据的进度条保持隐藏。
     * @param cardBinding 收起卡片的固定 XML 节点
     * @param planKey 当前 Key 及其本地缓存
     */
    private fun bindCollapsedQuotaProgress(
        cardBinding: ItemPlanUsageKeyBinding,
        planKey: SavedPlanKey
    ) {
        val usage = planKey.cachedUsage ?: return
        val shortQuotaResult = PlanUsageQuotaCalculator.calculateCycleQuota(
            usedUsd = usage.dailyUsedUsd,
            limitUsd = usage.dailyLimitUsd,
            remainingUsd = usage.dailyRemainingUsd
        )
        val weekQuotaResult = PlanUsageQuotaCalculator.calculateCycleQuota(
            usedUsd = usage.weeklyUsedUsd,
            limitUsd = usage.weeklyLimitUsd,
            remainingUsd = usage.weeklyRemainingUsd
        )
        val hasShortQuota = shortQuotaResult.usedRate != null
        val hasWeekQuota = weekQuotaResult.usedRate != null
        if (!hasShortQuota && !hasWeekQuota) {
            return
        }
        if (hasShortQuota) {
            updateProgressBar(
                cardBinding.pbCollapsedDayQuotaProgress,
                shortQuotaResult.usedRate,
                shortQuotaResult.isWarning
            )
            cardBinding.tvCollapsedDayQuotaLabel.visibility = View.VISIBLE
            cardBinding.pbCollapsedDayQuotaProgress.visibility = View.VISIBLE
        }
        if (hasWeekQuota) {
            updateProgressBar(
                cardBinding.pbCollapsedWeekQuotaProgress,
                weekQuotaResult.usedRate,
                weekQuotaResult.isWarning
            )
            cardBinding.tvCollapsedWeekQuotaLabel.visibility = View.VISIBLE
            cardBinding.pbCollapsedWeekQuotaProgress.visibility = View.VISIBLE
        }
        cardBinding.llCollapsedQuotaProgress.visibility = View.VISIBLE
    }

    /** 每次复用卡片前清空收起态的两条额度进度，避免上一张 Key 的数据残留。 */
    private fun resetCollapsedQuotaVisibility(cardBinding: ItemPlanUsageKeyBinding) = with(cardBinding) {
        llCollapsedQuotaProgress.visibility = View.GONE
        tvCollapsedDayQuotaLabel.visibility = View.GONE
        pbCollapsedDayQuotaProgress.visibility = View.GONE
        tvCollapsedWeekQuotaLabel.visibility = View.GONE
        pbCollapsedWeekQuotaProgress.visibility = View.GONE
    }

    /**
     * 展开的卡片沿用原有用量、到期时间和倍率样式，缓存不存在时明确展示可用的历史周期数据。
     * @param cardBinding 展开卡片的固定 XML 节点
     * @param planKey 当前 Key 及其本地缓存
     */
    private fun bindPlanKeyDetails(cardBinding: ItemPlanUsageKeyBinding, planKey: SavedPlanKey) {
        resetPlanKeyDetailVisibility(cardBinding)
        val context = cardBinding.root.context
        val usage = planKey.cachedUsage
        if (usage == null) {
            val legacyPeriod = planKey.legacyPeriod
            cardBinding.tvEmptyUsageHint.text = if (planKey.lastUpdatedAt == null) {
                context.getString(R.string.plan_usage_no_last_snapshot)
            } else {
                context.getString(R.string.plan_usage_no_snapshot)
            }
            cardBinding.tvEmptyUsageHint.visibility = View.VISIBLE
            cardBinding.llPrimaryDetails.visibility = View.VISIBLE
            val dayWindowLabel = PlanUsageFormatter.resolveWindowLabel(
                legacyPeriod?.dayWindowStartAt,
                legacyPeriod?.dayWindowEndAt,
                context.getString(R.string.plan_usage_short_cycle)
            )
            val weekWindowLabel = PlanUsageFormatter.resolveWindowLabel(
                legacyPeriod?.weekWindowStartAt,
                legacyPeriod?.weekWindowEndAt,
                context.getString(R.string.plan_usage_week_cycle)
            )
            bindDetailRow(
                cardBinding.llDetailRowFirst,
                cardBinding.tvDetailLabelFirst,
                cardBinding.tvDetailValueFirst,
                context.getString(R.string.plan_usage_label_start_at),
                PlanUsageFormatter.formatBeijingTime(
                    legacyPeriod?.startAt,
                    includeZoneLabel = false
                )
            )
            bindDetailRow(
                cardBinding.llDetailRowSecond,
                cardBinding.tvDetailLabelSecond,
                cardBinding.tvDetailValueSecond,
                context.getString(R.string.plan_usage_label_end_at),
                PlanUsageFormatter.formatBeijingTime(
                    legacyPeriod?.endAt,
                    includeZoneLabel = false
                )
            )
            bindDetailRow(
                cardBinding.llDetailRowThird,
                cardBinding.tvDetailLabelThird,
                cardBinding.tvDetailValueThird,
                context.getString(R.string.plan_usage_window_end, dayWindowLabel),
                PlanUsageFormatter.formatBeijingTime(
                    legacyPeriod?.dayWindowEndAt,
                    includeZoneLabel = false
                )
            )
            bindDetailRow(
                cardBinding.llDetailRowFourth,
                cardBinding.tvDetailLabelFourth,
                cardBinding.tvDetailValueFourth,
                context.getString(R.string.plan_usage_window_end, weekWindowLabel),
                formatWeekWindowEndValue(context, legacyPeriod?.weekWindowEndAt)
            )
            bindLastUpdatedRow(cardBinding, planKey)
            return
        }

        cardBinding.llPrimaryDetails.visibility = View.VISIBLE
        bindDetailRow(
            cardBinding.llDetailRowFirst,
            cardBinding.tvDetailLabelFirst,
            cardBinding.tvDetailValueFirst,
            context.getString(R.string.plan_usage_label_plan),
            formatPlanNameWithAlert(
                context = context,
                planName = usage.planName ?: context.getString(R.string.plan_usage_value_unavailable),
                endAt = usage.endAt,
                weekWindowEndAt = usage.weekWindowEndAt
            )
        )
        bindDetailRow(
            cardBinding.llDetailRowSecond,
            cardBinding.tvDetailLabelSecond,
            cardBinding.tvDetailValueSecond,
            context.getString(R.string.plan_usage_label_start_at),
            PlanUsageFormatter.formatBeijingTime(
                usage.startAt,
                includeZoneLabel = false
            )
        )
        bindDetailRow(
            cardBinding.llDetailRowThird,
            cardBinding.tvDetailLabelThird,
            cardBinding.tvDetailValueThird,
            context.getString(R.string.plan_usage_label_end_at),
            PlanUsageFormatter.formatBeijingTime(
                usage.endAt,
                includeZoneLabel = false
            )
        )
        cardBinding.llDetailRowFourth.visibility = View.GONE

        if (usage.hasCycleUsage()) {
            val dayWindowLabel = PlanUsageFormatter.resolveWindowLabel(
                usage.dayWindowStartAt,
                usage.dayWindowEndAt,
                context.getString(R.string.plan_usage_short_cycle)
            )
            val weekWindowLabel = PlanUsageFormatter.resolveWindowLabel(
                usage.weekWindowStartAt,
                usage.weekWindowEndAt,
                context.getString(R.string.plan_usage_week_cycle)
            )
            cardBinding.tvCycleTitle.visibility = View.VISIBLE
            bindUsageQuota(
                planKey = planKey,
                context = cardBinding.root.context,
                titleView = cardBinding.tvDayQuotaTitle,
                detailView = cardBinding.tvDayQuotaDetail,
                percentView = cardBinding.tvDayQuotaPercent,
                progressBar = cardBinding.pbDayQuotaProgress,
                title = context.getString(R.string.plan_usage_quota_title, dayWindowLabel),
                usedUsd = usage.dailyUsedUsd,
                limitUsd = usage.dailyLimitUsd,
                remainingUsd = usage.dailyRemainingUsd
            )
            cardBinding.llDayQuota.visibility = View.VISIBLE
            bindUsageQuota(
                planKey = planKey,
                context = cardBinding.root.context,
                titleView = cardBinding.tvWeekQuotaTitle,
                detailView = cardBinding.tvWeekQuotaDetail,
                percentView = cardBinding.tvWeekQuotaPercent,
                progressBar = cardBinding.pbWeekQuotaProgress,
                title = context.getString(R.string.plan_usage_quota_title, weekWindowLabel),
                usedUsd = usage.weeklyUsedUsd,
                limitUsd = usage.weeklyLimitUsd,
                remainingUsd = usage.weeklyRemainingUsd
            )
            cardBinding.llWeekQuota.visibility = View.VISIBLE
            bindWeeklyExhaustion(cardBinding, planKey, usage)
            bindDetailRow(
                cardBinding.llCycleWindowEndFirst,
                cardBinding.tvCycleWindowEndLabelFirst,
                cardBinding.tvCycleWindowEndValueFirst,
                context.getString(R.string.plan_usage_window_end, dayWindowLabel),
                PlanUsageFormatter.formatBeijingTime(
                    usage.dayWindowEndAt,
                    includeZoneLabel = false
                )
            )
            bindDetailRow(
                cardBinding.llCycleWindowEndSecond,
                cardBinding.tvCycleWindowEndLabelSecond,
                cardBinding.tvCycleWindowEndValueSecond,
                context.getString(R.string.plan_usage_window_end, weekWindowLabel),
                formatWeekWindowEndValue(context, usage.weekWindowEndAt)
            )
        }
        if (usage.hasResourceUsage()) {
            cardBinding.tvResourceTitle.visibility = View.VISIBLE
            cardBinding.llTokenQuota.visibility = View.VISIBLE
            bindTokenQuota(cardBinding, planKey)
        }
        if (!usage.hasResourceUsage() && !usage.hasCycleUsage()) {
            cardBinding.tvNoQuotaHint.visibility = View.VISIBLE
        }
        bindDetailRow(
            cardBinding.llGroupMultipliersRow,
            cardBinding.tvGroupMultipliersLabel,
            cardBinding.tvGroupMultipliersValue,
            context.getString(R.string.plan_usage_label_group_multipliers),
            formatGroupMultipliers(cardBinding.root.context, usage)
        )
        bindLastUpdatedRow(cardBinding, planKey)
    }

    /**
     * 卡片顶部优先展示本次临时刷新错误，否则展示最近一次确定的查询状态。
     * @param messageView 卡片标题下方的状态文本
     * @param planKey 当前 Key、最新查询状态和历史快照
     * @param latestError 当前页面会话内最近一次刷新错误
     */
    private fun bindStatusMessage(
        messageView: TextView,
        planKey: SavedPlanKey,
        latestError: String?
    ) {
        val statusMessage = latestError?.let { error ->
            if (planKey.cachedUsage == null && planKey.legacyPeriod == null) {
                messageView.context.getString(R.string.plan_usage_refresh_failed, error)
            } else {
                messageView.context.getString(R.string.plan_usage_refresh_failed_with_cache, error)
            }
        } ?: resolveQueryStatusMessage(messageView.context, planKey)
        messageView.text = statusMessage.orEmpty()
        messageView.visibility = if (statusMessage == null) View.GONE else View.VISIBLE
        val colorResId = when {
            latestError != null -> R.color.plan_usage_warning
            planKey.queryStatus == PlanUsageQueryStatus.EXPIRED ||
                planKey.queryStatus == PlanUsageQueryStatus.INVALID_API_KEY -> R.color.plan_usage_danger
            else -> R.color.plan_usage_warning
        }
        messageView.setTextColor(messageView.context.getColor(colorResId))
    }

    /**
     * 将持久化查询状态转换为确定文案，并根据是否有历史快照避免承诺不存在的数据。
     * @param context 当前卡片用于读取本地化资源的上下文
     * @param planKey 当前 Key、查询状态和历史数据
     * @return 无需提示时为 null，否则返回对应状态文案
     */
    private fun resolveQueryStatusMessage(context: Context, planKey: SavedPlanKey): String? {
        val hasHistoricalData = planKey.cachedUsage != null || planKey.legacyPeriod != null
        return when (planKey.queryStatus) {
            PlanUsageQueryStatus.ACTIVE -> null
            PlanUsageQueryStatus.EXPIRED -> if (hasHistoricalData) {
                context.getString(R.string.plan_usage_status_expired_with_cache)
            } else {
                context.getString(R.string.plan_usage_status_expired_without_cache)
            }
            PlanUsageQueryStatus.INVALID_API_KEY -> if (hasHistoricalData) {
                context.getString(R.string.plan_usage_status_invalid_key_with_cache)
            } else {
                context.getString(R.string.plan_usage_status_invalid_key)
            }
            PlanUsageQueryStatus.UNKNOWN -> if (hasHistoricalData) {
                context.getString(R.string.plan_usage_status_unknown_with_cache)
            } else {
                context.getString(R.string.plan_usage_status_unknown)
            }
        }
    }

    /**
     * 每次 RecyclerView 复用卡片前先收起可选区块，避免前一条 Key 的额度内容残留到下一条。
     * @param cardBinding 当前复用卡片的 Binding
     */
    private fun resetPlanKeyDetailVisibility(cardBinding: ItemPlanUsageKeyBinding) = with(cardBinding) {
        tvEmptyUsageHint.visibility = View.GONE
        llPrimaryDetails.visibility = View.GONE
        llDetailRowFirst.visibility = View.GONE
        llDetailRowSecond.visibility = View.GONE
        llDetailRowThird.visibility = View.GONE
        llDetailRowFourth.visibility = View.GONE
        tvCycleTitle.visibility = View.GONE
        llDayQuota.visibility = View.GONE
        llWeekQuota.visibility = View.GONE
        llWeekQuotaSpeed.visibility = View.GONE
        llWeekQuotaEstimate.visibility = View.GONE
        llCycleWindowEndFirst.visibility = View.GONE
        llCycleWindowEndSecond.visibility = View.GONE
        tvResourceTitle.visibility = View.GONE
        llTokenQuota.visibility = View.GONE
        tvNoQuotaHint.visibility = View.GONE
        llAllowedModelsRow.visibility = View.GONE
        llGroupMultipliersRow.visibility = View.GONE
        llLastUpdatedRow.visibility = View.GONE
    }

    /**
     * 将套餐到期和周额度重置告警合并到套餐名称后，套餐到期告警优先于周额度重置告警。
     * @param context 当前卡片用于读取本地化文案和告警颜色
     * @param planName 套餐名称
     * @param endAt 套餐到期时间
     * @param weekWindowEndAt 周额度窗口结束时间
     */
    private fun formatPlanNameWithAlert(
        context: Context,
        planName: String,
        endAt: String?,
        weekWindowEndAt: String?
    ): CharSequence {
        val nowMillis = System.currentTimeMillis()
        val alertText = when (val countdown = PlanUsageFormatter.resolveExpiryCountdown(endAt, nowMillis)) {
            null -> null
            PlanUsageFormatter.ExpiryCountdown.Expired ->
                context.getString(R.string.plan_usage_plan_expired)
            is PlanUsageFormatter.ExpiryCountdown.Remaining -> {
                if (!PlanUsageFormatter.isExpiryWithinWarningWindow(endAt, nowMillis)) {
                    null
                } else {
                    formatAlertCountdown(
                        context = context,
                        duration = countdown.duration,
                        daysHoursResId = R.string.plan_usage_plan_expiry_days_hours,
                        hoursMinutesResId = R.string.plan_usage_plan_expiry_hours_minutes,
                        minutesResId = R.string.plan_usage_plan_expiry_minutes
                    )
                }
            }
        } ?: when (val countdown = PlanUsageFormatter.resolveWeekResetCountdown(weekWindowEndAt, nowMillis)) {
            null -> null
            is PlanUsageFormatter.ResetCountdown.Remaining -> formatAlertCountdown(
                context = context,
                duration = countdown.duration,
                daysHoursResId = R.string.plan_usage_plan_reset_days_hours,
                hoursMinutesResId = R.string.plan_usage_plan_reset_hours_minutes,
                minutesResId = R.string.plan_usage_plan_reset_minutes
            )
        }
        if (alertText == null) {
            return planName
        }
        val fullText = context.getString(R.string.plan_usage_plan_with_alert, planName, alertText)
        return SpannableString(fullText).apply {
            val warningStart = fullText.lastIndexOf(alertText)
            if (warningStart >= 0) {
                setSpan(
                    ForegroundColorSpan(context.getColor(R.color.plan_usage_danger)),
                    warningStart - 1,
                    fullText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    /** 按告警时长选择组合单位文案，完整保留天/小时或小时/分钟的余量。 */
    private fun formatAlertCountdown(
        context: Context,
        duration: PlanUsageFormatter.CountdownDuration,
        daysHoursResId: Int,
        hoursMinutesResId: Int,
        minutesResId: Int
    ): String {
        return when {
            duration.days > 0L -> context.getString(
                daysHoursResId,
                duration.days,
                duration.hours
            )
            duration.hours > 0L -> context.getString(
                hoursMinutesResId,
                duration.hours,
                duration.minutes
            )
            else -> context.getString(minutesResId, duration.minutes)
        }
    }

    /**
     * 绑定一行固定的左右信息节点；标签宽度已在 XML 中约束，保证不同卡片扫读位置一致。
     * @param row 该行的容器
     * @param labelView 左侧字段名
     * @param valueView 右侧展示值
     * @param label 字段名
     * @param value 字段值
     */
    private fun bindDetailRow(
        row: View,
        labelView: TextView,
        valueView: TextView,
        label: String,
        value: CharSequence
    ) {
        labelView.text = label
        valueView.text = value
        row.visibility = View.VISIBLE
    }

    /**
     * 每张卡片单独显示最后成功更新时刻，用户可以区分缓存数据和本次刷新结果。
     * @param cardBinding 当前卡片 Binding
     * @param planKey 当前 Key 及其最后有效数据时间
     */
    private fun bindLastUpdatedRow(cardBinding: ItemPlanUsageKeyBinding, planKey: SavedPlanKey) {
        val hasAvailableSnapshot = planKey.cachedUsage != null
        val hasHistoricalData = hasAvailableSnapshot || planKey.legacyPeriod != null
        val timeMillis = if (hasHistoricalData) {
            planKey.lastUpdatedAt ?: planKey.lastCheckedAt
        } else {
            planKey.lastCheckedAt
        }
        bindDetailRow(
            cardBinding.llLastUpdatedRow,
            cardBinding.tvLastUpdatedLabel,
            cardBinding.tvLastUpdatedValue,
            when {
                !hasHistoricalData -> cardBinding.root.context.getString(R.string.plan_usage_last_check)
                planKey.queryStatus == PlanUsageQueryStatus.ACTIVE ->
                    cardBinding.root.context.getString(R.string.plan_usage_last_update)
                else -> cardBinding.root.context.getString(R.string.plan_usage_last_valid_data)
            },
            timeMillis?.let {
                PlanUsageFormatter.formatLocalTime(it, includeZoneLabel = false)
            }
                ?: cardBinding.root.context.getString(R.string.plan_usage_not_queried)
        )
    }

    /**
     * 在周窗口结束日期后补充固定重置日；缺少有效时间时仅展示日期占位符。
     * @param context 当前卡片用于读取本地化资源的上下文
     * @param windowEndAt 服务端返回的周窗口结束时间
     */
    private fun formatWeekWindowEndValue(
        context: Context,
        windowEndAt: String?
    ): String {
        val formattedTime = PlanUsageFormatter.formatBeijingTime(
            windowEndAt,
            includeZoneLabel = false
        )
        val resetWeekday = PlanUsageFormatter.resolveBeijingWeekday(windowEndAt)
        return resetWeekday?.let {
            context.getString(R.string.plan_usage_week_window_end_value, formattedTime, it)
        } ?: formattedTime
    }

    /**
     * 将周期订阅金额、预警颜色和渐变进度条同步更新，避免动态创建或拆分进度 View。
     * @param planKey 当前额度所属 Key，用于输出脱敏诊断信息
     * @param context 当前卡片的资源上下文
     * @param titleView 额度标题
     * @param detailView 已用、总额和剩余额度文本
     * @param percentView 已用比例文本
     * @param progressBar 根据实际已用比例绘制的系统进度控件
     * @param title 当前周期名称
     * @param usedUsd 服务端已用金额
     * @param limitUsd 服务端总额度
     * @param remainingUsd 服务端剩余额度
     */
    private fun bindUsageQuota(
        planKey: SavedPlanKey,
        context: Context,
        titleView: TextView,
        detailView: TextView,
        percentView: TextView,
        progressBar: ProgressBar,
        title: String,
        usedUsd: Double?,
        limitUsd: Double?,
        remainingUsd: Double?
    ) {
        val quotaResult = PlanUsageQuotaCalculator.calculateCycleQuota(
            usedUsd = usedUsd,
            limitUsd = limitUsd,
            remainingUsd = remainingUsd
        )
        val textColor = context.getColor(
            if (quotaResult.isWarning) R.color.plan_usage_danger else R.color.plan_usage_text_secondary
        )
        titleView.text = title
        detailView.text = context.getString(
            R.string.plan_usage_quota_detail,
            PlanUsageFormatter.formatUsd(quotaResult.displayUsedUsd),
            PlanUsageFormatter.formatUsd(limitUsd),
            PlanUsageFormatter.formatUsd(remainingUsd)
        )
        detailView.setTextColor(textColor)
        percentView.text = context.getString(
            R.string.plan_usage_used_percent,
            PlanUsageFormatter.formatPercent(quotaResult.usedRate)
        )
        percentView.setTextColor(textColor)
        updateProgressBar(progressBar, quotaResult.usedRate, quotaResult.isWarning)
        logCycleQuotaProgress(
            planKey = planKey,
            title = title,
            usedUsd = usedUsd,
            limitUsd = limitUsd,
            remainingUsd = remainingUsd,
            quotaResult = quotaResult,
            progressBar = progressBar
        )
    }

    /** 将周额度的速度和预计耗尽状态写入周进度条下方的固定文本节点。 */
    private fun bindWeeklyExhaustion(
        cardBinding: ItemPlanUsageKeyBinding,
        planKey: SavedPlanKey,
        usage: PlanUsageSnapshot
    ) {
        val context = cardBinding.root.context
        val speedRow = cardBinding.llWeekQuotaSpeed
        val estimateRow = cardBinding.llWeekQuotaEstimate
        val speedView = cardBinding.tvWeekQuotaSpeed
        val estimateView = cardBinding.tvWeekQuotaEstimate
        speedRow.visibility = View.GONE
        estimateRow.visibility = View.GONE

        when (val result = PlanUsageQuotaCalculator.estimateWeeklyExhaustion(
            usage = usage,
            sampleAtMillis = planKey.lastUpdatedAt
        )) {
            PlanUsageQuotaCalculator.WeeklyExhaustionResult.Exhausted -> {
                estimateView.text = context.getString(R.string.plan_usage_week_exhausted)
                estimateView.setTextColor(context.getColor(R.color.plan_usage_text_primary))
                estimateRow.visibility = View.VISIBLE
            }
            is PlanUsageQuotaCalculator.WeeklyExhaustionResult.Insufficient -> {
                estimateView.text = context.getString(
                    when (result.reason) {
                        PlanUsageQuotaCalculator.InsufficientReason.NO_USAGE ->
                            R.string.plan_usage_week_no_usage
                        PlanUsageQuotaCalculator.InsufficientReason.LOW_USAGE ->
                            R.string.plan_usage_week_low_usage
                        PlanUsageQuotaCalculator.InsufficientReason.INVALID_DATA ->
                            R.string.plan_usage_week_unavailable
                    }
                )
                estimateView.setTextColor(context.getColor(R.color.plan_usage_text_primary))
                estimateRow.visibility = View.VISIBLE
            }
            is PlanUsageQuotaCalculator.WeeklyExhaustionResult.WillExhaust -> {
                bindWeeklySpeed(speedRow, speedView, context, result.effectiveSpeedUsdPerHour)
                estimateView.text = context.getString(
                    R.string.plan_usage_week_exhaustion,
                    formatEstimateDuration(context, result.hoursUntilExhaustion)
                )
                estimateView.setTextColor(context.getColor(R.color.plan_usage_text_primary))
                estimateRow.visibility = View.VISIBLE
            }
            is PlanUsageQuotaCalculator.WeeklyExhaustionResult.WillSurviveReset -> {
                bindWeeklySpeed(speedRow, speedView, context, result.effectiveSpeedUsdPerHour)
                estimateView.text = context.getString(
                    R.string.plan_usage_week_exhaustion,
                    formatEstimateDuration(context, result.hoursUntilExhaustion)
                )
                estimateView.setTextColor(context.getColor(R.color.plan_usage_text_primary))
                estimateRow.visibility = View.VISIBLE
            }
            is PlanUsageQuotaCalculator.WeeklyExhaustionResult.NearReset -> {
                bindWeeklySpeed(speedRow, speedView, context, result.effectiveSpeedUsdPerHour)
                estimateView.text = context.getString(
                    R.string.plan_usage_week_exhaustion,
                    formatEstimateDuration(context, result.hoursUntilExhaustion)
                )
                estimateView.setTextColor(context.getColor(R.color.plan_usage_text_primary))
                estimateRow.visibility = View.VISIBLE
            }
        }
    }

    /** 将每小时速度转换为每天速度，并写入与窗口结束行一致的左右信息行。 */
    private fun bindWeeklySpeed(
        speedRow: View,
        speedView: TextView,
        context: Context,
        speedUsdPerHour: Double
    ) {
        speedView.text = context.getString(
            R.string.plan_usage_week_speed,
            PlanUsageFormatter.formatUsd(speedUsdPerHour * HOURS_PER_DAY)
        )
        speedView.setTextColor(context.getColor(R.color.plan_usage_text_primary))
        speedRow.visibility = View.VISIBLE
    }

    /** 将小时拆分为天、小时和分钟，避免预计耗尽时间只显示小数。 */
    private fun formatEstimateDuration(context: Context, hours: Double): String {
        val safeHours = hours.coerceAtLeast(0.0)
        val totalMinutes = (safeHours * MINUTES_PER_HOUR).toLong().coerceAtLeast(1L)
        return if (safeHours > HOURS_PER_DAY) {
            val days = totalMinutes / MINUTES_PER_DAY
            val remainingHours = (totalMinutes % MINUTES_PER_DAY) / MINUTES_PER_HOUR
            context.getString(
                R.string.plan_usage_duration_days_hours,
                days,
                remainingHours
            )
        } else {
            val hoursPart = totalMinutes / MINUTES_PER_HOUR
            val minutesPart = totalMinutes % MINUTES_PER_HOUR
            if (hoursPart >= 1L) {
                context.getString(
                    R.string.plan_usage_duration_hours_minutes,
                    hoursPart,
                    minutesPart
                )
            } else {
                context.getString(
                    R.string.plan_usage_duration_minutes,
                    totalMinutes
                )
            }
        }
    }

    /**
     * 将资源包 token 用量写入固定 XML 节点，和周期额度共用相同的预警阈值。
     * @param cardBinding 当前卡片 Binding
     * @param planKey 当前 Key 及其最后有效用量快照
     */
    private fun bindTokenQuota(cardBinding: ItemPlanUsageKeyBinding, planKey: SavedPlanKey) {
        val usage = planKey.cachedUsage ?: return
        val quotaResult = PlanUsageQuotaCalculator.calculateTokenQuota(usage)
        val textColor = cardBinding.root.context.getColor(
            if (quotaResult.isWarning) R.color.plan_usage_danger else R.color.plan_usage_text_secondary
        )
        cardBinding.tvTokenQuotaDetail.text = cardBinding.root.context.getString(
            R.string.plan_usage_quota_detail,
            PlanUsageFormatter.formatToken(usage.consumedTokens),
            PlanUsageFormatter.formatToken(quotaResult.totalTokens),
            PlanUsageFormatter.formatToken(usage.remainingTokens)
        )
        cardBinding.tvTokenQuotaDetail.setTextColor(textColor)
        cardBinding.tvTokenQuotaPercent.text = cardBinding.root.context.getString(
            R.string.plan_usage_used_percent,
            PlanUsageFormatter.formatPercent(quotaResult.usedRate)
        )
        cardBinding.tvTokenQuotaPercent.setTextColor(textColor)
        updateProgressBar(
            cardBinding.pbTokenQuotaProgress,
            quotaResult.usedRate,
            quotaResult.isWarning
        )
        logTokenQuotaProgress(
            planKey = planKey,
            usage = usage,
            quotaResult = quotaResult,
            progressBar = cardBinding.pbTokenQuotaProgress
        )
    }

    /**
     * 在 View 完成布局后记录周期额度的原始值、计算结果和 Drawable 实际等级。
     * @param planKey 当前额度所属 Key
     * @param title 日额度或周额度标题
     * @param usedUsd 服务端已用金额
     * @param limitUsd 服务端总额度
     * @param remainingUsd 服务端剩余额度
     * @param quotaResult 页面使用的额度计算结果
     * @param progressBar 当前额度进度控件
     */
    private fun logCycleQuotaProgress(
        planKey: SavedPlanKey,
        title: String,
        usedUsd: Double?,
        limitUsd: Double?,
        remainingUsd: Double?,
        quotaResult: PlanUsageQuotaCalculator.CycleQuotaResult,
        progressBar: ProgressBar
    ) {
        if (!BuildConfig.DEBUG) {
            return
        }
        progressBar.post {
            val expectedFilledWidth = progressBar.width * (quotaResult.usedRate ?: 0.0)
            Log.d(
                PLAN_USAGE_PROGRESS_LOG_TAG,
                "key=${PlanUsageFormatter.maskKey(planKey.apiKey)}，名称=${planKey.name}，" +
                    "额度=$title，rawUsedUsd=$usedUsd，rawLimitUsd=$limitUsd，" +
                    "rawRemainingUsd=$remainingUsd，displayUsedUsd=${quotaResult.displayUsedUsd}，" +
                    "usedRate=${quotaResult.usedRate}，" +
                    "percentText=${PlanUsageFormatter.formatPercent(quotaResult.usedRate)}，" +
                    "progress=${progressBar.progress}/${progressBar.max}，" +
                    "drawableLevel=${progressBar.progressDrawable?.level}，width=${progressBar.width}，" +
                    "expectedFilledWidth=$expectedFilledWidth，queryStatus=${planKey.queryStatus}"
            )
        }
    }

    /**
     * 在 View 完成布局后记录资源包 token 的原始值、计算结果和 Drawable 实际等级。
     * @param planKey 当前资源包所属 Key
     * @param usage 当前 Key 的最后有效用量快照
     * @param quotaResult 页面使用的 token 额度计算结果
     * @param progressBar token 进度控件
     */
    private fun logTokenQuotaProgress(
        planKey: SavedPlanKey,
        usage: PlanUsageSnapshot,
        quotaResult: PlanUsageQuotaCalculator.TokenQuotaResult,
        progressBar: ProgressBar
    ) {
        if (!BuildConfig.DEBUG) {
            return
        }
        progressBar.post {
            val expectedFilledWidth = progressBar.width * (quotaResult.usedRate ?: 0.0)
            Log.d(
                PLAN_USAGE_PROGRESS_LOG_TAG,
                "key=${PlanUsageFormatter.maskKey(planKey.apiKey)}，名称=${planKey.name}，额度=资源包，" +
                    "rawConsumedTokens=${usage.consumedTokens}，rawTotalTokens=${usage.totalTokens}，" +
                    "rawRemainingTokens=${usage.remainingTokens}，" +
                    "calculatedTotalTokens=${quotaResult.totalTokens}，usedRate=${quotaResult.usedRate}，" +
                    "percentText=${PlanUsageFormatter.formatPercent(quotaResult.usedRate)}，" +
                    "progress=${progressBar.progress}/${progressBar.max}，" +
                    "drawableLevel=${progressBar.progressDrawable?.level}，width=${progressBar.width}，" +
                    "expectedFilledWidth=$expectedFilledWidth，queryStatus=${planKey.queryStatus}"
            )
        }
    }

    /**
     * 将已用比例转换为系统控件的千分位进度，并按预警状态创建自适应进度 Drawable。
     * @param progressBar 当前额度对应的系统进度控件
     * @param usedRate 当前已用比例
     * @param isWarning 是否展示红橙预警渐变
     */
    private fun updateProgressBar(
        progressBar: ProgressBar,
        usedRate: Double?,
        isWarning: Boolean
    ) {
        val progressRate = (usedRate ?: 0.0).coerceIn(0.0, 1.0).toFloat()
        val quotaProgressDrawable = QuotaProgressDrawable(progressBar.context, isWarning)
        progressBar.progressDrawable = quotaProgressDrawable
        progressBar.progress = (progressRate * PROGRESS_MAX).roundToInt()
        quotaProgressDrawable.syncProgressRate(progressRate)
    }

    /**
     * 按服务端允许分组顺序展示名称和倍率，缺少名称时回退分组 ID 便于排查。
     * @param context 当前卡片的资源上下文
     * @param usage 当前 key 返回的用量快照
     */
    private fun formatGroupMultipliers(context: Context, usage: PlanUsageSnapshot): CharSequence {
        val groupIds = linkedSetOf<String>().apply {
            addAll(usage.allowedGroups)
            addAll(usage.groupMultipliers.keys)
            addAll(usage.groupNames.keys)
        }
        if (groupIds.isEmpty()) {
            return context.getString(R.string.plan_usage_value_unavailable)
        }
        val colorRanges = mutableListOf<Triple<Int, Int, Int>>()
        val textBuilder = StringBuilder()
        groupIds.forEachIndexed { index, groupId ->
            if (index > 0) {
                textBuilder.append(context.getString(R.string.plan_usage_list_separator))
            }
            val start = textBuilder.length
            val groupName = usage.groupNames[groupId] ?: groupId
            val multiplierValue = usage.groupMultipliers[groupId]
            val multiplier = multiplierValue?.let {
                context.getString(R.string.plan_usage_multiplier, PlanUsageFormatter.formatDecimal(it))
            } ?: context.getString(
                R.string.plan_usage_multiplier,
                context.getString(R.string.plan_usage_value_unavailable)
            )
            textBuilder.append("$groupName $multiplier")
            val end = textBuilder.length
            resolveGroupMultiplierColorResId(groupId, groupName, multiplierValue)?.let { colorResId ->
                colorRanges.add(Triple(start, end, colorResId))
            }
        }
        return SpannableString(textBuilder).apply {
            colorRanges.forEach { (start, end, colorResId) ->
                setSpan(
                    ForegroundColorSpan(context.getColor(colorResId)),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    /**
     * 根据分组倍率与默认基线的差异返回展示颜色：低于基线为绿色，高于基线为红色。
     * @param groupId 服务端返回的分组 ID
     * @param groupName 服务端返回的分组名称
     * @param multiplier 当前 key 对应的分组倍率
     */
    private fun resolveGroupMultiplierColorResId(groupId: String, groupName: String, multiplier: Double?): Int? {
        return when (
            PlanUsageQuotaCalculator.resolveGroupMultiplierTrend(groupId, groupName, multiplier)
        ) {
            PlanUsageQuotaCalculator.GroupMultiplierTrend.LOWER -> R.color.plan_usage_success
            PlanUsageQuotaCalculator.GroupMultiplierTrend.HIGHER -> R.color.plan_usage_danger
            null -> null
        }
    }

    private companion object {
        /** 预计耗尽时间按整数分钟拆分，避免展示小数小时。 */
        private const val MINUTES_PER_HOUR = 60L
        private const val MINUTES_PER_DAY = 24L * MINUTES_PER_HOUR
        /** ProgressBar 以 0.1% 为最小单位，避免 0.9% 等小用量被截断为零。 */
        private const val PROGRESS_MAX = 1_000
        /** 速度统一以每小时计算，页面展示转换为每天便于用户理解。 */
        private const val HOURS_PER_DAY = 24.0
        /** 额度绘制诊断统一使用该 Tag，便于在 Logcat 中单独过滤。 */
        private const val PLAN_USAGE_PROGRESS_LOG_TAG = "PlanUsageProgress"
    }
}
