package com.hss.myroutin.adapter

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hss.myroutin.R
import com.hss.myroutin.databinding.ItemPlanUsageKeyBinding
import com.hss.myroutin.model.PlanUsageSnapshot
import com.hss.myroutin.model.SavedPlanKey
import com.hss.myroutin.widget.QuotaProgressDrawable
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * 说明：订阅 Key 卡片列表适配器，负责将缓存数据格式化并绑定到 XML 卡片；Activity 只处理页面级交互。
 *
 * @作者 huangssh
 * @版本 2.3
 */
class PlanUsageKeyAdapter(
    private val onTogglePlanKey: (String) -> Unit,
    private val onManagePlanKey: (View, SavedPlanKey) -> Unit
) : ListAdapter<PlanUsageKeyAdapter.KeyCardItem, PlanUsageKeyAdapter.PlanUsageKeyViewHolder>(
    KEY_CARD_ITEM_DIFF_CALLBACK
) {

    /** 卡片金额、百分比和 token 均使用稳定格式，避免列表滚动时产生不同展示精度。 */
    private val usdFormatter = DecimalFormat("0.##")
    private val percentFormatter = DecimalFormat("0.#")
    private val tokenFormatter = DecimalFormat("#,###")

    init {
        setHasStableIds(true)
    }

    /**
     * 使用当前排序后的 Key 列表更新卡片，刷新态只在本次页面会话内生效。
     * @param keys 已排序的订阅 Key
     * @param refreshingKeyIds 正在请求的 Key ID 集合
     * @param latestErrorByKeyId 当前页面会话内的最近刷新错误
     */
    fun submit(
        keys: List<SavedPlanKey>,
        refreshingKeyIds: Set<String>,
        latestErrorByKeyId: Map<String, String>
    ) {
        submitList(keys.map { key ->
            KeyCardItem(
                key = key,
                isRefreshing = key.id in refreshingKeyIds,
                latestError = latestErrorByKeyId[key.id]
            )
        })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlanUsageKeyViewHolder {
        val binding = ItemPlanUsageKeyBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlanUsageKeyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlanUsageKeyViewHolder, position: Int) {
        val item = getItem(position)
        bindPlanKeyCard(holder.binding, item.key, item.isRefreshing, item.latestError)
    }

    override fun getItemId(position: Int): Long = getItem(position).key.id.hashCode().toLong()

    /**
     * 为复用的 XML 卡片写入当前 Key 数据，所有可变区块只切换已有节点的内容和可见性。
     * @param cardBinding 当前 ViewHolder 对应的卡片 Binding
     * @param planKey 当前 Key 的持久化数据和缓存
     * @param isRefreshing 当前 Key 是否正在请求
     * @param latestError 当前页面会话内最近一次刷新错误
     */
    private fun bindPlanKeyCard(
        cardBinding: ItemPlanUsageKeyBinding,
        planKey: SavedPlanKey,
        isRefreshing: Boolean,
        latestError: String?
    ) = with(cardBinding) {
        tvKeyName.text = planKey.name
        tvMaskedKey.text = maskKey(planKey.apiKey)
        tvKeyRefreshing.visibility = if (isRefreshing) View.VISIBLE else View.INVISIBLE
        tvToggle.text = if (planKey.isExpanded) "收起" else "展开"
        tvRefreshError.text = latestError?.let { "本次刷新失败：$it，保留上次数据" }.orEmpty()
        tvRefreshError.visibility = if (latestError == null) View.GONE else View.VISIBLE
        llKeyDetails.visibility = if (planKey.isExpanded) View.VISIBLE else View.GONE
        tvCollapsedHint.visibility = if (planKey.isExpanded) View.GONE else View.VISIBLE
        llKeyHeader.setOnClickListener { onTogglePlanKey(planKey.id) }
        tvToggle.setOnClickListener { onTogglePlanKey(planKey.id) }
        tvManage.setOnClickListener { onManagePlanKey(tvManage, planKey) }
        if (planKey.isExpanded) {
            bindPlanKeyDetails(cardBinding, planKey)
        }
    }

    /**
     * 展开的卡片沿用原有用量、到期时间和倍率样式，缓存不存在时明确引导用户使用整体刷新。
     * @param cardBinding 展开卡片的固定 XML 节点
     * @param planKey 当前 Key 及其本地缓存
     */
    private fun bindPlanKeyDetails(cardBinding: ItemPlanUsageKeyBinding, planKey: SavedPlanKey) {
        resetPlanKeyDetailVisibility(cardBinding)
        val usage = planKey.cachedUsage
        if (usage == null) {
            cardBinding.tvEmptyUsageHint.text = if (planKey.lastUpdatedAt == null) {
                "暂无缓存，点击刷新全部获取最新额度"
            } else {
                "当前 Key 无可用订阅或额度已耗尽"
            }
            cardBinding.tvEmptyUsageHint.visibility = View.VISIBLE
            cardBinding.llPrimaryDetails.visibility = View.VISIBLE
            val dayWindowLabel = resolveWindowLabel(
                planKey.cachedDayWindowStartAt,
                planKey.cachedDayWindowEndAt,
                FALLBACK_SHORT_CYCLE_LABEL
            )
            val weekWindowLabel = resolveWindowLabel(
                planKey.cachedWeekWindowStartAt,
                planKey.cachedWeekWindowEndAt,
                FALLBACK_WEEK_CYCLE_LABEL
            )
            bindDetailRow(
                cardBinding.llDetailRowFirst,
                cardBinding.tvDetailLabelFirst,
                cardBinding.tvDetailValueFirst,
                "开始时间",
                formatBeijingTime(planKey.cachedStartAt)
            )
            bindDetailRow(
                cardBinding.llDetailRowSecond,
                cardBinding.tvDetailLabelSecond,
                cardBinding.tvDetailValueSecond,
                "到期时间",
                formatBeijingTime(planKey.cachedEndAt)
            )
            bindDetailRow(
                cardBinding.llDetailRowThird,
                cardBinding.tvDetailLabelThird,
                cardBinding.tvDetailValueThird,
                "${dayWindowLabel}窗口结束",
                formatWindowEndAt(planKey.cachedDayWindowEndAt)
            )
            bindDetailRow(
                cardBinding.llDetailRowFourth,
                cardBinding.tvDetailLabelFourth,
                cardBinding.tvDetailValueFourth,
                "${weekWindowLabel}窗口结束",
                formatWindowEndAt(planKey.cachedWeekWindowEndAt)
            )
            bindLastUpdatedRow(cardBinding, planKey.lastUpdatedAt)
            return
        }

        cardBinding.llPrimaryDetails.visibility = View.VISIBLE
        bindDetailRow(
            cardBinding.llDetailRowFirst,
            cardBinding.tvDetailLabelFirst,
            cardBinding.tvDetailValueFirst,
            "套餐",
            usage.planName ?: "--"
        )
        bindDetailRow(
            cardBinding.llDetailRowSecond,
            cardBinding.tvDetailLabelSecond,
            cardBinding.tvDetailValueSecond,
            "类型/状态",
            "${usage.type ?: "--"} / ${usage.status ?: "--"}"
        )
        bindDetailRow(
            cardBinding.llDetailRowThird,
            cardBinding.tvDetailLabelThird,
            cardBinding.tvDetailValueThird,
            "开始时间",
            formatBeijingTime(usage.startAt ?: planKey.cachedStartAt)
        )
        bindDetailRow(
            cardBinding.llDetailRowFourth,
            cardBinding.tvDetailLabelFourth,
            cardBinding.tvDetailValueFourth,
            "到期时间",
            formatBeijingTime(usage.endAt ?: planKey.cachedEndAt)
        )

        if (usage.hasCycleUsage()) {
            val dayWindowLabel = resolveWindowLabel(
                usage.dayWindowStartAt ?: planKey.cachedDayWindowStartAt,
                usage.dayWindowEndAt ?: planKey.cachedDayWindowEndAt,
                FALLBACK_SHORT_CYCLE_LABEL
            )
            val weekWindowLabel = resolveWindowLabel(
                usage.weekWindowStartAt ?: planKey.cachedWeekWindowStartAt,
                usage.weekWindowEndAt ?: planKey.cachedWeekWindowEndAt,
                FALLBACK_WEEK_CYCLE_LABEL
            )
            cardBinding.tvCycleTitle.visibility = View.VISIBLE
            bindUsageQuota(
                context = cardBinding.root.context,
                titleView = cardBinding.tvDayQuotaTitle,
                detailView = cardBinding.tvDayQuotaDetail,
                percentView = cardBinding.tvDayQuotaPercent,
                progressBar = cardBinding.pbDayQuotaProgress,
                title = "${dayWindowLabel}额度",
                usedUsd = usage.dailyUsedUsd,
                limitUsd = usage.dailyLimitUsd,
                remainingUsd = usage.dailyRemainingUsd
            )
            cardBinding.llDayQuota.visibility = View.VISIBLE
            bindUsageQuota(
                context = cardBinding.root.context,
                titleView = cardBinding.tvWeekQuotaTitle,
                detailView = cardBinding.tvWeekQuotaDetail,
                percentView = cardBinding.tvWeekQuotaPercent,
                progressBar = cardBinding.pbWeekQuotaProgress,
                title = "${weekWindowLabel}额度",
                usedUsd = usage.weeklyUsedUsd,
                limitUsd = usage.weeklyLimitUsd,
                remainingUsd = usage.weeklyRemainingUsd
            )
            cardBinding.llWeekQuota.visibility = View.VISIBLE
            bindDetailRow(
                cardBinding.llCycleWindowEndFirst,
                cardBinding.tvCycleWindowEndLabelFirst,
                cardBinding.tvCycleWindowEndValueFirst,
                "${dayWindowLabel}窗口结束",
                formatWindowEndAt(usage.dayWindowEndAt ?: planKey.cachedDayWindowEndAt)
            )
            bindDetailRow(
                cardBinding.llCycleWindowEndSecond,
                cardBinding.tvCycleWindowEndLabelSecond,
                cardBinding.tvCycleWindowEndValueSecond,
                "${weekWindowLabel}窗口结束",
                formatWindowEndAt(usage.weekWindowEndAt ?: planKey.cachedWeekWindowEndAt)
            )
        }
        if (usage.hasResourceUsage()) {
            cardBinding.tvResourceTitle.visibility = View.VISIBLE
            cardBinding.llTokenQuota.visibility = View.VISIBLE
            bindTokenQuota(cardBinding, usage)
        }
        if (!usage.hasResourceUsage() && !usage.hasCycleUsage()) {
            cardBinding.tvNoQuotaHint.visibility = View.VISIBLE
        }
        bindDetailRow(
            cardBinding.llAllowedModelsRow,
            cardBinding.tvAllowedModelsLabel,
            cardBinding.tvAllowedModelsValue,
            "允许模型",
            usage.allowedModels.takeIf { it.isNotEmpty() }?.joinToString() ?: "--"
        )
        bindDetailRow(
            cardBinding.llGroupMultipliersRow,
            cardBinding.tvGroupMultipliersLabel,
            cardBinding.tvGroupMultipliersValue,
            "分组倍率",
            formatGroupMultipliers(cardBinding.root.context, usage)
        )
        bindLastUpdatedRow(cardBinding, planKey.lastUpdatedAt)
    }

    /**
     * 每次 RecyclerView 复用卡片前先收起可选区块，避免前一条 Key 的额度内容残留到下一条。
     * @param cardBinding 当前复用卡片的 Binding
     */
    private fun resetPlanKeyDetailVisibility(cardBinding: ItemPlanUsageKeyBinding) = with(cardBinding) {
        tvEmptyUsageHint.visibility = View.GONE
        llPrimaryDetails.visibility = View.GONE
        tvCycleTitle.visibility = View.GONE
        llDayQuota.visibility = View.GONE
        llWeekQuota.visibility = View.GONE
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
     * @param lastUpdatedAt 本地缓存最后成功更新时间
     */
    private fun bindLastUpdatedRow(cardBinding: ItemPlanUsageKeyBinding, lastUpdatedAt: Long?) {
        bindDetailRow(
            cardBinding.llLastUpdatedRow,
            cardBinding.tvLastUpdatedLabel,
            cardBinding.tvLastUpdatedValue,
            "上次更新",
            lastUpdatedAt?.let { formatLocalTime(it) } ?: "未查询"
        )
    }

    /**
     * 将周期订阅金额、预警颜色和渐变进度条同步更新，避免动态创建或拆分进度 View。
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
        val displayUsedUsd = calculateDisplayUsedUsd(usedUsd, limitUsd, remainingUsd)
        val usedRate = calculateUsedRate(displayUsedUsd, limitUsd)
        val isWarning = isCycleQuotaExhausted(limitUsd, remainingUsd) ||
            isProgressOverWarningThreshold(usedRate)
        val textColor = context.getColor(
            if (isWarning) R.color.plan_usage_danger else R.color.plan_usage_text_secondary
        )
        titleView.text = title
        detailView.text = "已用 ${formatUsd(displayUsedUsd)} / ${formatUsd(limitUsd)}，剩余 ${formatUsd(remainingUsd)}"
        detailView.setTextColor(textColor)
        percentView.text = "已用${formatPercent(usedRate)}"
        percentView.setTextColor(textColor)
        updateProgressBar(progressBar, usedRate, isWarning)
    }

    /**
     * 将资源包 token 用量写入固定 XML 节点，和周期额度共用相同的预警阈值。
     * @param cardBinding 当前卡片 Binding
     * @param usage 当前 Key 返回的用量快照
     */
    private fun bindTokenQuota(cardBinding: ItemPlanUsageKeyBinding, usage: PlanUsageSnapshot) {
        val total = calculateTokenTotal(usage.totalTokens, usage.consumedTokens, usage.remainingTokens)
        val usedRate = calculateTokenUsedRate(usage.consumedTokens, total)
        val isWarning = isProgressOverWarningThreshold(usedRate)
        val textColor = cardBinding.root.context.getColor(
            if (isWarning) R.color.plan_usage_danger else R.color.plan_usage_text_secondary
        )
        cardBinding.tvTokenQuotaDetail.text =
            "已用 ${formatToken(usage.consumedTokens)} / ${formatToken(total)}，剩余 ${formatToken(usage.remainingTokens)}"
        cardBinding.tvTokenQuotaDetail.setTextColor(textColor)
        cardBinding.tvTokenQuotaPercent.text = "已用${formatPercent(usedRate)}"
        cardBinding.tvTokenQuotaPercent.setTextColor(textColor)
        updateProgressBar(
            cardBinding.pbTokenQuotaProgress,
            usedRate,
            isWarning
        )
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
        progressBar.progressDrawable = QuotaProgressDrawable(progressBar.context, isWarning)
        progressBar.progress = (progressRate * PROGRESS_MAX).roundToInt()
    }

    /**
     * 周期接口只给剩余额度时，用 limit-remaining 补出展示已用值。
     */
    private fun calculateDisplayUsedUsd(usedUsd: Double?, limitUsd: Double?, remainingUsd: Double?): Double? {
        if (usedUsd != null) {
            return usedUsd
        }
        if (limitUsd != null && remainingUsd != null && limitUsd > 0.0) {
            return (limitUsd - remainingUsd).coerceIn(0.0, limitUsd)
        }
        return usedUsd
    }

    private fun calculateUsedRate(usedUsd: Double?, limitUsd: Double?): Double? {
        if (usedUsd == null || limitUsd == null || limitUsd <= 0.0) {
            return null
        }
        return (usedUsd / limitUsd).coerceIn(0.0, 1.0)
    }

    /**
     * 周期额度有正额度且剩余额度小于等于 0 时，按额度耗尽处理。
     */
    private fun isCycleQuotaExhausted(limitUsd: Double?, remainingUsd: Double?): Boolean {
        return limitUsd != null && limitUsd > 0.0 && remainingUsd != null && remainingUsd <= 0.0
    }

    /**
     * 已用比例超过 80% 时进入预警态，说明文本切换危险色，渐变条本身仍按真实已用比例着色。
     * @param usedRate 当前已用比例
     */
    private fun isProgressOverWarningThreshold(usedRate: Double?): Boolean {
        return usedRate != null && usedRate > PROGRESS_WARNING_THRESHOLD
    }

    private fun calculateTokenTotal(totalTokens: Long?, consumedTokens: Long?, remainingTokens: Long?): Long? {
        if (totalTokens != null && totalTokens > 0L) {
            return totalTokens
        }
        if (consumedTokens != null && remainingTokens != null) {
            return consumedTokens + remainingTokens
        }
        return totalTokens
    }

    private fun calculateTokenUsedRate(consumedTokens: Long?, totalTokens: Long?): Double? {
        if (consumedTokens == null || totalTokens == null || totalTokens <= 0L) {
            return null
        }
        return (consumedTokens.toDouble() / totalTokens.toDouble()).coerceIn(0.0, 1.0)
    }

    /** 多 Key 的窗口时间已经随各自条目缓存，格式化时不再读取全局 SP 字段。 */
    private fun formatWindowEndAt(windowEndAt: String?): String {
        return formatBeijingTime(windowEndAt)
    }

    /**
     * 根据服务端返回的窗口起止时间确定展示周期，接口调整重置频率时不再依赖固定的日/周文案。
     * @param windowStartAt 服务端窗口开始时间
     * @param windowEndAt 服务端窗口结束时间
     * @param fallbackLabel 旧缓存缺少开始时间时的保守展示名称
     */
    private fun resolveWindowLabel(windowStartAt: String?, windowEndAt: String?, fallbackLabel: String): String {
        val startTime = parseWindowEndAt(windowStartAt)?.time ?: return fallbackLabel
        val endTime = parseWindowEndAt(windowEndAt)?.time ?: return fallbackLabel
        val durationMinutes = (endTime - startTime) / MILLIS_PER_MINUTE
        if (durationMinutes <= 0L) {
            return fallbackLabel
        }
        return when (durationMinutes) {
            MINUTES_PER_DAY -> "日"
            MINUTES_PER_WEEK -> "周"
            else -> when {
                durationMinutes % MINUTES_PER_DAY == 0L -> "${durationMinutes / MINUTES_PER_DAY}天"
                durationMinutes % MINUTES_PER_HOUR == 0L -> "${durationMinutes / MINUTES_PER_HOUR}小时"
                else -> "${durationMinutes}分钟"
            }
        }
    }

    /** 将服务端 UTC 窗口结束时间固定展示为北京时间。 */
    private fun formatBeijingTime(windowEndAt: String?): String {
        val date = parseWindowEndAt(windowEndAt) ?: return "--"
        return "${BEIJING_TIME_FORMAT.format(date)} 北京时间"
    }

    /** 本地缓存更新时间以北京时间展示，和服务端返回的窗口时间保持同一种阅读习惯。 */
    private fun formatLocalTime(timeMillis: Long): String {
        return "${BEIJING_TIME_FORMAT.format(Date(timeMillis))} 北京时间"
    }

    private fun parseWindowEndAt(windowEndAt: String?): Date? {
        if (windowEndAt.isNullOrBlank()) {
            return null
        }
        ISO_DATE_FORMATS.forEach { dateFormat ->
            runCatching { dateFormat.parse(windowEndAt) }.getOrNull()?.let { date ->
                return date
            }
        }
        return null
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
            return "--"
        }
        val colorRanges = mutableListOf<Triple<Int, Int, Int>>()
        val textBuilder = StringBuilder()
        groupIds.forEachIndexed { index, groupId ->
            if (index > 0) {
                textBuilder.append("，")
            }
            val start = textBuilder.length
            val groupName = usage.groupNames[groupId] ?: groupId
            val multiplierValue = usage.groupMultipliers[groupId]
            val multiplier = multiplierValue?.let { "x${usdFormatter.format(it)}" } ?: "x--"
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
        val defaultMultiplier = resolveDefaultGroupMultiplier(groupId, groupName) ?: return null
        return when {
            multiplier == null || multiplier == defaultMultiplier -> null
            multiplier < defaultMultiplier -> R.color.plan_usage_success
            else -> R.color.plan_usage_danger
        }
    }

    /**
     * 默认倍率基线来自当前套餐规则：Codex Pro 为 x2，Codex 为 x1。
     * @param groupId 服务端返回的分组 ID
     * @param groupName 服务端返回的分组名称
     */
    private fun resolveDefaultGroupMultiplier(groupId: String, groupName: String): Double? {
        return when {
            groupId == GROUP_ID_CODEX_PRO || groupName == GROUP_NAME_CODEX_PRO -> DEFAULT_CODEX_PRO_GROUP_MULTIPLIER
            groupId == GROUP_ID_CODEX || groupName == GROUP_NAME_CODEX -> DEFAULT_CODEX_GROUP_MULTIPLIER
            else -> null
        }
    }

    private fun formatPercent(usedRate: Double?): String {
        return usedRate?.let { "${percentFormatter.format(it * 100)}%" } ?: "--"
    }

    private fun formatUsd(value: Double?): String {
        return value?.let { "${'$'}${usdFormatter.format(it)}" } ?: "--"
    }

    private fun formatToken(value: Long?): String {
        return value?.let { tokenFormatter.format(it) } ?: "--"
    }

    private fun maskKey(apiKey: String): String {
        return if (apiKey.length <= MASK_KEY_SHORT_LENGTH) {
            "${apiKey.take(4)}****"
        } else {
            "${apiKey.take(9)}****${apiKey.takeLast(6)}"
        }
    }

    /**
     * 单张卡片的完整比较对象，包含持久数据和会话内刷新态，确保 DiffUtil 不会漏掉状态变化。
     */
    data class KeyCardItem(
        val key: SavedPlanKey,
        val isRefreshing: Boolean,
        val latestError: String?
    )

    /** XML 卡片通过 ViewBinding 持有，避免每次绑定时重新创建整棵 View 树。 */
    class PlanUsageKeyViewHolder(
        val binding: ItemPlanUsageKeyBinding
    ) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        /** 以 Key ID 确认同一张卡片，以完整展示状态决定是否需要重新绑定。 */
        private val KEY_CARD_ITEM_DIFF_CALLBACK = object : DiffUtil.ItemCallback<KeyCardItem>() {
            override fun areItemsTheSame(oldItem: KeyCardItem, newItem: KeyCardItem): Boolean {
                return oldItem.key.id == newItem.key.id
            }

            override fun areContentsTheSame(oldItem: KeyCardItem, newItem: KeyCardItem): Boolean {
                return oldItem == newItem
            }
        }

        private const val PROGRESS_WARNING_THRESHOLD = 0.8
        /** ProgressBar 以 0.1% 为最小单位，避免 0.9% 等小用量被截断为零。 */
        private const val PROGRESS_MAX = 1_000
        private const val FALLBACK_SHORT_CYCLE_LABEL = "短周期"
        private const val FALLBACK_WEEK_CYCLE_LABEL = "周"
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MINUTES_PER_HOUR = 60L
        private const val MINUTES_PER_DAY = 24L * MINUTES_PER_HOUR
        private const val MINUTES_PER_WEEK = 7L * MINUTES_PER_DAY
        /** 分组默认倍率基线，用于判断接口返回倍率是否低于常规值。 */
        private const val GROUP_ID_CODEX_PRO = "ffa027fc-8402-4b99-8db2-66eefc87325f"
        private const val GROUP_ID_CODEX = "ffa2f93c-6a1f-4bbd-a968-632ae3654465"
        private const val GROUP_NAME_CODEX_PRO = "Codex Pro"
        private const val GROUP_NAME_CODEX = "Codex"
        private const val DEFAULT_CODEX_PRO_GROUP_MULTIPLIER = 2.0
        private const val DEFAULT_CODEX_GROUP_MULTIPLIER = 1.0
        private const val MASK_KEY_SHORT_LENGTH = 15
        private val BEIJING_TIME_ZONE = TimeZone.getTimeZone("Asia/Shanghai")
        private val BEIJING_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
            timeZone = BEIJING_TIME_ZONE
        }
        private val ISO_DATE_FORMATS = listOf<DateFormat>(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        )
    }
}
