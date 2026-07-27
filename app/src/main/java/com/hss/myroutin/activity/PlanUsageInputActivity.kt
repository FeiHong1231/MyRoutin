package com.hss.myroutin.activity

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.hss.myroutin.R
import com.hss.myroutin.adapter.PlanUsageKeyAdapter
import com.hss.myroutin.databinding.ActivityPlanUsageInputBinding
import com.hss.myroutin.databinding.DialogRenamePlanKeyBinding
import com.hss.myroutin.databinding.ItemPlanUsageKeyBinding
import com.hss.myroutin.model.PlanUsageSnapshot
import com.hss.myroutin.model.SavedPlanKey
import com.hss.myroutin.viewmodel.PlanUsageUiEvent
import com.hss.myroutin.viewmodel.PlanUsageUiState
import com.hss.myroutin.viewmodel.PlanUsageViewModel
import com.hss.myroutin.widget.MyToastD
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 说明：订阅 Key 用量查询页，支持本地保存多个 Key 并集中查看额度。
 *
 * @作者 huangssh
 * @版本 2.1
 */
class PlanUsageInputActivity : AppCompatActivity() {

    /** 页面固定结构由 XML 描述，Activity 只通过 Binding 更新状态和分发交互。 */
    private lateinit var binding: ActivityPlanUsageInputBinding

    private val usdFormatter = DecimalFormat("0.##")
    private val percentFormatter = DecimalFormat("0.#")
    private val tokenFormatter = DecimalFormat("#,###")

    /**
     * 页面只从 ViewModel 获取状态，避免 Activity 同时承担请求、缓存和列表状态职责。
     */
    private val viewModel by lazy {
        ViewModelProvider(this).get(PlanUsageViewModel::class.java)
    }

    /** RecyclerView 仅复用 XML 卡片；每次绑定根据当前 Key 状态显示对应的固定节点。 */
    private lateinit var planUsageKeyAdapter: PlanUsageKeyAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "订阅 Key 查询"
        binding = ActivityPlanUsageInputBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initializePage()
        observeViewModel()
    }

    /**
     * 初始化固定页面控件和点击入口，避免在运行时拼装根布局与卡片容器。
     */
    private fun initializePage() {
        planUsageKeyAdapter = PlanUsageKeyAdapter(::renderPlanKeyCard)
        binding.rvPlanKeys.apply {
            layoutManager = LinearLayoutManager(this@PlanUsageInputActivity)
            adapter = planUsageKeyAdapter
            isNestedScrollingEnabled = true
        }
        binding.btnAddKey.setOnClickListener { toggleAddKeyPanel() }
        binding.btnRefreshAll.setOnClickListener { refreshAllPlanKeys() }
        binding.btnPasteKey.setOnClickListener { pasteApiKeyFromClipboard() }
        binding.btnQueryAndAdd.setOnClickListener { queryAndAddPlanKey() }
    }

    /**
     * 持续渲染 ViewModel 状态，并单独消费键盘、滚动和 Toast 等一次性事件。
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect(::renderPage)
                }
                launch {
                    viewModel.events.collect(::handleUiEvent)
                }
            }
        }
    }

    /**
     * Activity 仅处理必须依赖 View 的短暂副作用，业务状态已由 ViewModel 完成更新。
     * @param event ViewModel 发出的单次 UI 事件
     */
    private fun handleUiEvent(event: PlanUsageUiEvent) {
        when (event) {
            is PlanUsageUiEvent.ShowToast -> MyToastD.show(event.message)
            is PlanUsageUiEvent.ScrollToPlanKey -> scrollToPlanKey(event.keyId)
            PlanUsageUiEvent.HideKeyboard -> hideKeyboard()
            PlanUsageUiEvent.ClearAddKeyInputs -> {
                binding.etKeyName.setText("")
                binding.etApiKey.setText("")
            }
        }
    }

    /**
     * 将剪贴板第一段文本填入输入框，方便用户直接复制 plan key 后查询。
     */
    private fun pasteApiKeyFromClipboard() {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboardManager
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (clipText.isBlank()) {
            MyToastD.show("剪贴板为空")
            return
        }
        binding.etApiKey.setText(clipText)
        binding.etApiKey.setSelection(clipText.length)
        MyToastD.show("已粘贴")
    }

    /**
     * 依据 ViewModel 输出的唯一状态刷新数量、控件状态和列表内容。
     * @param state 当前页面的完整渲染状态
     */
    private fun renderPage(state: PlanUsageUiState) {
        binding.tvKeyCount.text = "我的 Key（${state.planKeys.size}）"
        binding.tvRefreshStatus.text = when {
            state.isRefreshingAll -> "刷新中 ${state.refreshCurrentIndex}/${state.refreshTotalCount}"
            !state.refreshStatusText.isNullOrBlank() -> state.refreshStatusText
            else -> ""
        }
        binding.tvRefreshStatus.visibility = if (binding.tvRefreshStatus.text.isNullOrBlank()) {
            View.GONE
        } else {
            View.VISIBLE
        }
        binding.btnAddKey.isEnabled = !state.isRefreshingAll
        binding.btnAddKey.text = if (state.isAddKeyPanelVisible) "收起" else "添加 Key"
        binding.btnRefreshAll.isEnabled = state.planKeys.isNotEmpty() && !state.isRefreshingAll
        binding.btnRefreshAll.text = if (state.isRefreshingAll) "刷新中..." else "刷新全部"
        binding.btnQueryAndAdd.isEnabled = !state.isAddingKey && !state.isRefreshingAll
        binding.btnQueryAndAdd.text = if (state.isAddingKey) "查询中..." else "查询并添加"
        binding.btnPasteKey.isEnabled = !state.isAddingKey && !state.isRefreshingAll
        binding.llAddKeyPanel.visibility = if (state.isAddKeyPanelVisible) View.VISIBLE else View.GONE
        binding.tvEmptyHint.visibility = if (state.planKeys.isEmpty()) View.VISIBLE else View.GONE
        binding.rvPlanKeys.visibility = if (state.planKeys.isEmpty()) View.GONE else View.VISIBLE
        planUsageKeyAdapter.submit(state.planKeys, state.refreshingKeyIds, state.latestErrorByKeyId)
    }

    /**
     * 添加入口的可见性由 ViewModel 更新，Activity 只保留输入框聚焦和收起键盘的 View 操作。
     */
    private fun toggleAddKeyPanel() {
        val wasVisible = viewModel.uiState.value.isAddKeyPanelVisible
        viewModel.toggleAddKeyPanel()
        val isVisible = viewModel.uiState.value.isAddKeyPanelVisible
        if (wasVisible == isVisible) {
            return
        }
        if (isVisible) {
            binding.etApiKey.requestFocus()
        } else {
            hideKeyboard()
        }
    }

    /** 将当前输入框内容交给 ViewModel 校验、查询并保存。 */
    private fun queryAndAddPlanKey() {
        viewModel.queryAndAddPlanKey(
            rawName = binding.etKeyName.text?.toString().orEmpty(),
            rawApiKey = binding.etApiKey.text?.toString().orEmpty()
        )
    }

    /** 批量刷新策略由 ViewModel 管理，Activity 仅分发按钮点击。 */
    private fun refreshAllPlanKeys() {
        viewModel.refreshAllPlanKeys()
    }

    /** 将指定 Key 定位到 ViewModel 已排序的当前列表位置。 */
    private fun scrollToPlanKey(keyId: String) {
        val index = viewModel.uiState.value.planKeys.indexOfFirst { it.id == keyId }
        if (index >= 0) {
            binding.rvPlanKeys.post { binding.rvPlanKeys.smoothScrollToPosition(index) }
        }
    }

    /**
     * 为复用的 XML 卡片写入当前 Key 数据，所有可变区块只切换已有节点的内容和可见性。
     * @param cardBinding 当前 ViewHolder 对应的卡片 Binding
     * @param planKey 当前 Key 的持久化数据和缓存
     * @param isRefreshing 当前 Key 是否正在请求
     * @param latestError 当前页面会话内最近一次刷新错误
     */
    private fun renderPlanKeyCard(
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
        llKeyHeader.setOnClickListener { viewModel.togglePlanKeyExpansion(planKey.id) }
        tvToggle.setOnClickListener { viewModel.togglePlanKeyExpansion(planKey.id) }
        tvManage.setOnClickListener { showPlanKeyMenu(tvManage, planKey) }
        if (planKey.isExpanded) {
            renderPlanKeyDetails(cardBinding, planKey)
        }
    }

    /**
     * 展开的卡片沿用原有用量、到期时间和倍率样式，缓存不存在时明确引导用户使用整体刷新。
     * @param cardBinding 展开卡片的固定 XML 节点
     * @param planKey 当前 Key 及其本地缓存
     */
    private fun renderPlanKeyDetails(cardBinding: ItemPlanUsageKeyBinding, planKey: SavedPlanKey) {
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
                titleView = cardBinding.tvDayQuotaTitle,
                detailView = cardBinding.tvDayQuotaDetail,
                percentView = cardBinding.tvDayQuotaPercent,
                progressFill = cardBinding.vDayQuotaProgressFill,
                progressSpacer = cardBinding.vDayQuotaProgressSpacer,
                title = "${dayWindowLabel}额度",
                usedUsd = usage.dailyUsedUsd,
                limitUsd = usage.dailyLimitUsd,
                remainingUsd = usage.dailyRemainingUsd
            )
            cardBinding.llDayQuota.visibility = View.VISIBLE
            bindUsageQuota(
                titleView = cardBinding.tvWeekQuotaTitle,
                detailView = cardBinding.tvWeekQuotaDetail,
                percentView = cardBinding.tvWeekQuotaPercent,
                progressFill = cardBinding.vWeekQuotaProgressFill,
                progressSpacer = cardBinding.vWeekQuotaProgressSpacer,
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
            formatGroupMultipliers(usage)
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
     * 将周期订阅金额、预警颜色和 XML 进度条权重同步更新，避免动态创建进度 View。
     * @param titleView 额度标题
     * @param detailView 已用、总额和剩余额度文本
     * @param percentView 已用比例文本
     * @param progressFill 进度条前景节点
     * @param progressSpacer 进度条剩余空间节点
     * @param title 当前周期名称
     * @param usedUsd 服务端已用金额
     * @param limitUsd 服务端总额度
     * @param remainingUsd 服务端剩余额度
     */
    private fun bindUsageQuota(
        titleView: TextView,
        detailView: TextView,
        percentView: TextView,
        progressFill: View,
        progressSpacer: View,
        title: String,
        usedUsd: Double?,
        limitUsd: Double?,
        remainingUsd: Double?
    ) {
        val displayUsedUsd = calculateDisplayUsedUsd(usedUsd, limitUsd, remainingUsd)
        val usedRate = calculateUsedRate(displayUsedUsd, limitUsd)
        val isWarning = isCycleQuotaExhausted(limitUsd, remainingUsd) ||
            isProgressOverWarningThreshold(usedRate)
        val textColor = getColorCompat(if (isWarning) R.color.red_ff3b30 else R.color.gray_727272)
        titleView.text = title
        detailView.text = "已用 ${formatUsd(displayUsedUsd)} / ${formatUsd(limitUsd)}，剩余 ${formatUsd(remainingUsd)}"
        detailView.setTextColor(textColor)
        percentView.text = "已用${formatPercent(usedRate)}"
        percentView.setTextColor(textColor)
        updateProgressBar(progressFill, progressSpacer, usedRate, isWarning)
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
        val textColor = getColorCompat(if (isWarning) R.color.red_ff3b30 else R.color.gray_727272)
        cardBinding.tvTokenQuotaDetail.text =
            "已用 ${formatToken(usage.consumedTokens)} / ${formatToken(total)}，剩余 ${formatToken(usage.remainingTokens)}"
        cardBinding.tvTokenQuotaDetail.setTextColor(textColor)
        cardBinding.tvTokenQuotaPercent.text = "已用${formatPercent(usedRate)}"
        cardBinding.tvTokenQuotaPercent.setTextColor(textColor)
        updateProgressBar(
            cardBinding.vTokenQuotaProgressFill,
            cardBinding.vTokenQuotaProgressSpacer,
            usedRate,
            isWarning
        )
    }

    /**
     * 通过 XML 中两个固定子节点的权重表示进度，零用量不创建前景节点且仍保留底轨。
     * @param progressFill 进度条前景节点
     * @param progressSpacer 进度条剩余空间节点
     * @param usedRate 当前已用比例
     * @param isWarning 是否使用预警渐变
     */
    private fun updateProgressBar(
        progressFill: View,
        progressSpacer: View,
        usedRate: Double?,
        isWarning: Boolean
    ) {
        val progressRate = (usedRate ?: 0.0).coerceIn(0.0, 1.0).toFloat()
        val progressWeight = if (progressRate <= 0f) {
            0f
        } else {
            (progressRate * PROGRESS_WEIGHT_TOTAL).coerceAtLeast(1f)
        }
        val remainingWeight = (PROGRESS_WEIGHT_TOTAL - progressWeight).coerceAtLeast(0f)
        val fillLayoutParams = progressFill.layoutParams as LinearLayout.LayoutParams
        fillLayoutParams.weight = progressWeight
        progressFill.layoutParams = fillLayoutParams
        val spacerLayoutParams = progressSpacer.layoutParams as LinearLayout.LayoutParams
        spacerLayoutParams.weight = remainingWeight
        progressSpacer.layoutParams = spacerLayoutParams
        progressFill.setBackgroundResource(
            if (isWarning) R.drawable.bg_plan_usage_progress_warning else R.drawable.bg_plan_usage_progress_normal
        )
    }

    /**
     * 管理菜单提供单步排序、命名和删除，刻意不加入单卡刷新以保持用户确认的整体刷新规则。
     */
    private fun showPlanKeyMenu(anchor: View, planKey: SavedPlanKey) {
        val state = viewModel.uiState.value
        if (state.isRefreshingAll) {
            MyToastD.show("正在刷新全部 Key")
            return
        }
        val currentPosition = state.planKeys.indexOfFirst { it.id == planKey.id }
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_MOVE_UP, 0, "上移一位").isEnabled = currentPosition > 0
            menu.add(0, MENU_MOVE_DOWN, 1, "下移一位").isEnabled = currentPosition in 0 until state.planKeys.lastIndex
            menu.add(0, MENU_RENAME, 2, "重命名")
            menu.add(0, MENU_DELETE, 3, "删除")
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    MENU_MOVE_UP -> viewModel.movePlanKeyByOne(planKey.id, MOVE_OFFSET_UP)
                    MENU_MOVE_DOWN -> viewModel.movePlanKeyByOne(planKey.id, MOVE_OFFSET_DOWN)
                    MENU_RENAME -> showRenameDialog(planKey)
                    MENU_DELETE -> showDeleteDialog(planKey)
                }
                true
            }
            show()
        }
    }

    /**
     * 自定义名称仅用于本地识别，不会影响接口请求中的原始 Key。
     */
    private fun showRenameDialog(planKey: SavedPlanKey) {
        val dialogBinding = DialogRenamePlanKeyBinding.inflate(layoutInflater)
        dialogBinding.etPlanKeyName.setText(planKey.name)
        val dialog = AlertDialog.Builder(this)
            .setTitle("重命名 Key")
            .setView(dialogBinding.root)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = dialogBinding.etPlanKeyName.text?.toString()?.trim().orEmpty()
                if (newName.isBlank()) {
                    MyToastD.show("名称不能为空")
                    return@setOnClickListener
                }
                viewModel.renamePlanKey(planKey.id, newName)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * 删除会移除本地 Key 与对应缓存，使用二次确认避免误操作后丢失查询配置。
     */
    private fun showDeleteDialog(planKey: SavedPlanKey) {
        AlertDialog.Builder(this)
            .setTitle("删除 ${planKey.name}")
            .setMessage("删除后将移除此 Key 的本地缓存，是否继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                viewModel.deletePlanKey(planKey.id)
            }
            .show()
    }

    /**
     * 收起输入法，避免新增或整体刷新后输入框已经隐藏但键盘仍停留在页面上。
     */
    private fun hideKeyboard() {
        binding.etApiKey.clearFocus()
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputMethodManager?.hideSoftInputFromWindow(binding.etApiKey.windowToken, 0)
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
     * 已用比例超过 80% 时进入预警态，进度说明和进度条同步使用红色。
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

    /**
     * 多 Key 的窗口时间已经随各自条目缓存，格式化时不再读取全局 SP 字段。
     */
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

    /**
     * 将服务端 UTC 窗口结束时间固定展示为北京时间。
     */
    private fun formatBeijingTime(windowEndAt: String?): String {
        val date = parseWindowEndAt(windowEndAt) ?: return "--"
        return "${BEIJING_TIME_FORMAT.format(date)} 北京时间"
    }

    /**
     * 本地缓存更新时间以北京时间展示，和服务端返回的窗口时间保持同一种阅读习惯。
     */
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
     * @param usage 当前 key 返回的用量快照
     */
    private fun formatGroupMultipliers(usage: PlanUsageSnapshot): CharSequence {
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
                    ForegroundColorSpan(getColorCompat(colorResId)),
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
            multiplier < defaultMultiplier -> R.color.green_34c759
            else -> R.color.red_ff3b30
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
        return if (apiKey.length <= 15) {
            "${apiKey.take(4)}****"
        } else {
            "${apiKey.take(9)}****${apiKey.takeLast(6)}"
        }
    }

    private fun getColorCompat(colorId: Int): Int {
        return resources.getColor(colorId, theme)
    }

    companion object {
        private const val PROGRESS_WARNING_THRESHOLD = 0.8
        private const val PROGRESS_WEIGHT_TOTAL = 1000f
        private const val FALLBACK_SHORT_CYCLE_LABEL = "短周期"
        private const val FALLBACK_WEEK_CYCLE_LABEL = "周"
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MINUTES_PER_HOUR = 60L
        private const val MINUTES_PER_DAY = 24L * MINUTES_PER_HOUR
        private const val MINUTES_PER_WEEK = 7L * MINUTES_PER_DAY
        private const val MENU_MOVE_UP = 1
        private const val MENU_MOVE_DOWN = 2
        private const val MENU_RENAME = 3
        private const val MENU_DELETE = 4
        private const val MOVE_OFFSET_UP = -1
        private const val MOVE_OFFSET_DOWN = 1
        /**
         * 分组默认倍率基线，用于判断接口返回倍率是否低于常规值。
         */
        private const val GROUP_ID_CODEX_PRO = "ffa027fc-8402-4b99-8db2-66eefc87325f"
        private const val GROUP_ID_CODEX = "ffa2f93c-6a1f-4bbd-a968-632ae3654465"
        private const val GROUP_NAME_CODEX_PRO = "Codex Pro"
        private const val GROUP_NAME_CODEX = "Codex"
        private const val DEFAULT_CODEX_PRO_GROUP_MULTIPLIER = 2.0
        private const val DEFAULT_CODEX_GROUP_MULTIPLIER = 1.0
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
