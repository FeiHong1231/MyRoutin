package com.hss.myroutin.activity

import android.content.Context
import android.content.ClipboardManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
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
import androidx.recyclerview.widget.RecyclerView
import com.hss.myroutin.R
import com.hss.myroutin.adapter.PlanUsageKeyAdapter
import com.hss.myroutin.model.PlanUsageSnapshot
import com.hss.myroutin.model.SavedPlanKey
import com.hss.myroutin.viewmodel.PlanUsageUiEvent
import com.hss.myroutin.viewmodel.PlanUsageUiState
import com.hss.myroutin.viewmodel.PlanUsageViewModel
import com.hss.myroutin.widget.MyToastD
import com.hss.myroutin.widget.dp
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

    /**
     * 添加 Key 时使用的名称与 Key 输入框，仅在添加面板展开时显示。
     */
    private lateinit var etKeyName: EditText
    private lateinit var etApiKey: EditText
    private lateinit var llAddKeyPanel: LinearLayout
    private lateinit var tvKeyCount: TextView
    private lateinit var tvRefreshStatus: TextView
    private lateinit var tvEmptyHint: TextView
    private lateinit var btnAddKey: Button
    private lateinit var btnRefreshAll: Button
    /**
     * 添加面板内从剪贴板填充 Key 的快捷入口。
     */
    private lateinit var btnPasteKey: Button
    private lateinit var btnQueryAndAdd: Button
    private lateinit var rvPlanKeys: RecyclerView
    private val usdFormatter = DecimalFormat("0.##")
    private val percentFormatter = DecimalFormat("0.#")
    private val tokenFormatter = DecimalFormat("#,###")

    /**
     * 页面只从 ViewModel 获取状态，避免 Activity 同时承担请求、缓存和列表状态职责。
     */
    private val viewModel by lazy {
        ViewModelProvider(this).get(PlanUsageViewModel::class.java)
    }

    private lateinit var planUsageKeyAdapter: PlanUsageKeyAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "订阅 Key 查询"
        setContentView(createContentView())
        observeViewModel()
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
                etKeyName.setText("")
                etApiKey.setText("")
            }
        }
    }

    /**
     * 页面顶部固定管理入口，Key 卡片交给 RecyclerView 滚动，避免多项展开时页面操作区被挤走。
     */
    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColorCompat(R.color.white_f5f6fa))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 8.dp)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        header.addView(createHeaderTitleRow())
        header.addView(createHeaderActionRow())
        llAddKeyPanel = createAddKeyPanel()
        header.addView(llAddKeyPanel)
        tvEmptyHint = createText("添加 Key 后可集中查看各订阅的额度和到期时间", 13f, R.color.gray_727272, false).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 14.dp
            }
            gravity = Gravity.CENTER
        }
        header.addView(tvEmptyHint)

        planUsageKeyAdapter = PlanUsageKeyAdapter(::renderPlanKeyCard)
        rvPlanKeys = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PlanUsageInputActivity)
            adapter = planUsageKeyAdapter
            isNestedScrollingEnabled = true
            clipToPadding = false
            setPadding(0, 4.dp, 0, 16.dp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        root.addView(header)
        root.addView(rvPlanKeys)
        return root
    }

    /**
     * 标题行同时显示当前数量和整体刷新进度，避免刷新状态分散到每一张卡片外。
     */
    private fun createHeaderTitleRow(): LinearLayout {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            tvKeyCount = createText("我的 Key（0）", 20f, R.color.gray_212121, true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            tvRefreshStatus = createText("", 13f, R.color.blue_2771fa, false).apply {
                gravity = Gravity.END
            }
            addView(tvKeyCount)
            addView(tvRefreshStatus)
        }
    }

    /**
     * 添加和整体刷新保持并列，按钮高度、间距与现有工具页操作区统一。
     */
    private fun createHeaderActionRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10.dp
            }
            btnAddKey = Button(this@PlanUsageInputActivity).apply {
                text = "添加 Key"
                isAllCaps = false
                setOnClickListener { toggleAddKeyPanel() }
                layoutParams = LinearLayout.LayoutParams(0, 46.dp, 1f).apply {
                    marginEnd = 6.dp
                }
            }
            btnRefreshAll = Button(this@PlanUsageInputActivity).apply {
                text = "刷新全部"
                isAllCaps = false
                setOnClickListener { refreshAllPlanKeys() }
                layoutParams = LinearLayout.LayoutParams(0, 46.dp, 1f).apply {
                    marginStart = 6.dp
                }
            }
            addView(btnAddKey)
            addView(btnRefreshAll)
        }
    }

    /**
     * 添加面板使用和结果卡片相同的圆角白底，避免输入区与列表卡片割裂。
     */
    private fun createAddKeyPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 14.dp, 14.dp, 14.dp)
            background = createPanelBackgroundDrawable()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12.dp
            }
            addView(createText("添加订阅 Key", 16f, R.color.gray_212121, true))
            etKeyName = EditText(this@PlanUsageInputActivity).apply {
                hint = "名称（可选，例如主力 Key）"
                isSingleLine = true
                setPadding(12.dp, 0, 12.dp, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    44.dp
                ).apply {
                    topMargin = 10.dp
                }
            }
            etApiKey = EditText(this@PlanUsageInputActivity).apply {
                hint = "请输入 plan- 开头的 apikey"
                isSingleLine = true
                // API Key 属于长期有效凭证，默认掩码展示以降低旁观泄露风险。
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSelectAllOnFocus(true)
                setPadding(12.dp, 0, 12.dp, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    44.dp
                ).apply {
                    topMargin = 8.dp
                }
            }
            addView(etKeyName)
            addView(etApiKey)
            val actionRow = LinearLayout(this@PlanUsageInputActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 10.dp
                }
            }
            btnPasteKey = Button(this@PlanUsageInputActivity).apply {
                text = "粘贴"
                isAllCaps = false
                setOnClickListener { pasteApiKeyFromClipboard() }
                layoutParams = LinearLayout.LayoutParams(0, 46.dp, 1f).apply {
                    marginEnd = 6.dp
                }
            }
            btnQueryAndAdd = Button(this@PlanUsageInputActivity).apply {
                text = "查询并添加"
                isAllCaps = false
                setOnClickListener { queryAndAddPlanKey() }
                layoutParams = LinearLayout.LayoutParams(0, 46.dp, 2f).apply {
                    marginStart = 6.dp
                }
            }
            actionRow.addView(btnPasteKey)
            actionRow.addView(btnQueryAndAdd)
            addView(actionRow)
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
        etApiKey.setText(clipText)
        etApiKey.setSelection(clipText.length)
        MyToastD.show("已粘贴")
    }

    /**
     * 依据 ViewModel 输出的唯一状态刷新数量、控件状态和列表内容。
     * @param state 当前页面的完整渲染状态
     */
    private fun renderPage(state: PlanUsageUiState) {
        tvKeyCount.text = "我的 Key（${state.planKeys.size}）"
        tvRefreshStatus.text = when {
            state.isRefreshingAll -> "刷新中 ${state.refreshCurrentIndex}/${state.refreshTotalCount}"
            !state.refreshStatusText.isNullOrBlank() -> state.refreshStatusText
            else -> ""
        }
        tvRefreshStatus.visibility = if (tvRefreshStatus.text.isNullOrBlank()) View.GONE else View.VISIBLE
        btnAddKey.isEnabled = !state.isRefreshingAll
        btnAddKey.text = if (state.isAddKeyPanelVisible) "收起" else "添加 Key"
        btnRefreshAll.isEnabled = state.planKeys.isNotEmpty() && !state.isRefreshingAll
        btnRefreshAll.text = if (state.isRefreshingAll) "刷新中..." else "刷新全部"
        btnQueryAndAdd.isEnabled = !state.isAddingKey && !state.isRefreshingAll
        btnQueryAndAdd.text = if (state.isAddingKey) "查询中..." else "查询并添加"
        btnPasteKey.isEnabled = !state.isAddingKey && !state.isRefreshingAll
        llAddKeyPanel.visibility = if (state.isAddKeyPanelVisible) View.VISIBLE else View.GONE
        tvEmptyHint.visibility = if (state.planKeys.isEmpty()) View.VISIBLE else View.GONE
        rvPlanKeys.visibility = if (state.planKeys.isEmpty()) View.GONE else View.VISIBLE
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
            etApiKey.requestFocus()
        } else {
            hideKeyboard()
        }
    }

    /** 将当前输入框内容交给 ViewModel 校验、查询并保存。 */
    private fun queryAndAddPlanKey() {
        viewModel.queryAndAddPlanKey(
            rawName = etKeyName.text?.toString().orEmpty(),
            rawApiKey = etApiKey.text?.toString().orEmpty()
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
            rvPlanKeys.post { rvPlanKeys.smoothScrollToPosition(index) }
        }
    }

    /**
     * 卡片容器由 RecyclerView 复用，页面层根据持久状态补齐完整的展示内容和交互入口。
     */
    private fun renderPlanKeyCard(
        card: LinearLayout,
        planKey: SavedPlanKey,
        isRefreshing: Boolean,
        latestError: String?
    ) {
        card.removeAllViews()
        val header = LinearLayout(this).apply {
            // 操作区顶部对齐标题，避免标题高度变化时按钮纵向漂移。
            gravity = Gravity.TOP
            orientation = LinearLayout.HORIZONTAL
            isClickable = true
            setOnClickListener {
                viewModel.togglePlanKeyExpansion(planKey.id)
            }
        }
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            orientation = LinearLayout.HORIZONTAL
        }
        titleRow.addView(createText(planKey.name, 18f, R.color.gray_212121, true).apply {
            ellipsize = TextUtils.TruncateAt.END
            isSingleLine = true
            // 为右侧操作预留空间，避免窄屏下标题挤压卡片操作入口。
            maxWidth = 170.dp
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        titleRow.addView(createText("正在刷新...", 12f, R.color.blue_2771fa, false).apply {
            isSingleLine = true
            visibility = if (isRefreshing) View.VISIBLE else View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(64.dp, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 6.dp
            }
        })
        titleColumn.addView(titleRow)
        titleColumn.addView(createText(maskKey(planKey.apiKey), 12f, R.color.gray_999999, false).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 2.dp
            }
        })
        val toggleView = createText(if (planKey.isExpanded) "收起" else "展开", 13f, R.color.blue_2771fa, false).apply {
            background = createCardActionBackgroundDrawable()
            setPadding(10.dp, 0, 10.dp, 0)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                32.dp
            )
            setOnClickListener {
                viewModel.togglePlanKeyExpansion(planKey.id)
            }
        }
        val manageView = createText("管理", 13f, R.color.blue_2771fa, false).apply {
            background = createCardActionBackgroundDrawable()
            setPadding(10.dp, 0, 10.dp, 0)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                32.dp
            ).apply {
                marginStart = 6.dp
            }
            setOnClickListener { showPlanKeyMenu(this, planKey) }
        }
        val actionRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(toggleView)
            addView(manageView)
        }
        header.addView(titleColumn)
        header.addView(actionRow)
        card.addView(header)

        latestError?.let { error ->
            card.addView(createText("本次刷新失败：$error，保留上次数据", 13f, R.color.orange_fe5d36, false).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 10.dp
                }
            })
        }
        if (!planKey.isExpanded) {
            card.addView(createText("已收起，点击卡片标题展开详情", 13f, R.color.gray_727272, false).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 10.dp
                }
            })
            return
        }
        renderPlanKeyDetails(card, planKey)
    }

    /**
     * 展开的卡片沿用原有用量、到期时间和倍率样式，缓存不存在时明确引导用户使用整体刷新。
     */
    private fun renderPlanKeyDetails(card: LinearLayout, planKey: SavedPlanKey) {
        val usage = planKey.cachedUsage
        if (usage == null) {
            val message = if (planKey.lastUpdatedAt == null) {
                "暂无缓存，点击刷新全部获取最新额度"
            } else {
                "当前 Key 无可用订阅或额度已耗尽"
            }
            card.addView(createText(message, 14f, R.color.gray_727272, false).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 10.dp
                }
            })
            addCachedTimeRows(card, planKey)
            addLastUpdatedRow(card, planKey.lastUpdatedAt)
            return
        }
        addRow(card, "套餐", usage.planName ?: "--")
        addRow(card, "类型/状态", "${usage.type ?: "--"} / ${usage.status ?: "--"}")
        addRow(card, "开始时间", formatBeijingTime(usage.startAt ?: planKey.cachedStartAt))
        addRow(card, "到期时间", formatBeijingTime(usage.endAt ?: planKey.cachedEndAt))
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
            addSectionTitle(card, "周期订阅")
            addUsageProgress(card, "${dayWindowLabel}额度", usage.dailyUsedUsd, usage.dailyLimitUsd, usage.dailyRemainingUsd)
            addUsageProgress(card, "${weekWindowLabel}额度", usage.weeklyUsedUsd, usage.weeklyLimitUsd, usage.weeklyRemainingUsd)
            addRow(card, "${dayWindowLabel}窗口结束", formatWindowEndAt(usage.dayWindowEndAt ?: planKey.cachedDayWindowEndAt))
            addRow(card, "${weekWindowLabel}窗口结束", formatWindowEndAt(usage.weekWindowEndAt ?: planKey.cachedWeekWindowEndAt))
        }
        if (usage.hasResourceUsage()) {
            addSectionTitle(card, "资源包套餐")
            addTokenProgress(card, usage.totalTokens, usage.consumedTokens, usage.remainingTokens)
        }
        if (!usage.hasResourceUsage() && !usage.hasCycleUsage()) {
            card.addView(createText("暂无可展示额度", 14f, R.color.gray_727272, false).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 10.dp
                }
            })
        }
        addRow(card, "允许模型", usage.allowedModels.takeIf { it.isNotEmpty() }?.joinToString() ?: "--")
        addRow(card, "分组倍率", formatGroupMultipliers(usage))
        addLastUpdatedRow(card, planKey.lastUpdatedAt)
    }

    /**
     * 无订阅或刷新失败时仍展示已缓存的订阅周期和窗口时间，避免关键时间信息随额度耗尽消失。
     */
    private fun addCachedTimeRows(card: LinearLayout, planKey: SavedPlanKey) {
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
        addRow(card, "开始时间", formatBeijingTime(planKey.cachedStartAt))
        addRow(card, "到期时间", formatBeijingTime(planKey.cachedEndAt))
        addRow(card, "${dayWindowLabel}窗口结束", formatWindowEndAt(planKey.cachedDayWindowEndAt))
        addRow(card, "${weekWindowLabel}窗口结束", formatWindowEndAt(planKey.cachedWeekWindowEndAt))
    }

    /**
     * 每张卡片单独显示最后成功更新时刻，用户可以区分缓存数据和本次刷新结果。
     */
    private fun addLastUpdatedRow(card: LinearLayout, lastUpdatedAt: Long?) {
        val lastUpdatedText = lastUpdatedAt?.let { formatLocalTime(it) } ?: "未查询"
        addRow(card, "上次更新", lastUpdatedText)
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
        val input = EditText(this).apply {
            setText(planKey.name)
            setSelectAllOnFocus(true)
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                52.dp
            )
        }
        val inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 2.dp, 24.dp, 6.dp)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("重命名 Key")
            .setView(inputContainer)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = input.text?.toString()?.trim().orEmpty()
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
        etApiKey.clearFocus()
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputMethodManager?.hideSoftInputFromWindow(etApiKey.windowToken, 0)
    }

    /**
     * 添加区域与列表卡片共用白色圆角背景，让页面在多模块时仍保持同一层级。
     */
    private fun createPanelBackgroundDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 10.dp.toFloat()
            setColor(getColorCompat(R.color.white))
        }
    }

    /**
     * 卡片操作使用浅蓝圆角底色，明确点击边界但不抢占 Key 名称和额度信息的视觉层级。
     */
    private fun createCardActionBackgroundDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 8.dp.toFloat()
            setColor(getColorCompat(R.color.blue_e8f0ff))
        }
    }

    private fun createText(textValue: CharSequence, textSizeSp: Float, colorId: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = textSizeSp
            setTextColor(getColorCompat(colorId))
            includeFontPadding = true
            if (bold) {
                typeface = Typeface.DEFAULT_BOLD
            }
        }
    }

    /**
     * 添加左右结构信息行，左侧字段名固定宽度便于快速扫读。
     */
    private fun addRow(parent: LinearLayout, label: String, value: CharSequence) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 6.dp
            }
        }
        row.addView(createText(label, 13f, R.color.gray_727272, false).apply {
            layoutParams = LinearLayout.LayoutParams(92.dp, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        row.addView(createText(value, 13f, R.color.gray_212121, false).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        parent.addView(row)
    }

    /**
     * 周期订阅和资源包套餐分区显示，避免美元额度和 token 额度混在一起。
     */
    private fun addSectionTitle(parent: LinearLayout, title: String) {
        parent.addView(createText(title, 15f, R.color.blue_2771fa, true).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12.dp
            }
        })
    }

    /**
     * 添加周期订阅进度条，额度耗尽时使用红色展示。
     */
    private fun addUsageProgress(
        parent: LinearLayout,
        title: String,
        usedUsd: Double?,
        limitUsd: Double?,
        remainingUsd: Double?
    ) {
        val displayUsedUsd = calculateDisplayUsedUsd(usedUsd, limitUsd, remainingUsd)
        val usedRate = calculateUsedRate(displayUsedUsd, limitUsd)
        val isExhausted = isCycleQuotaExhausted(limitUsd, remainingUsd)
        val isWarning = isExhausted || isProgressOverWarningThreshold(usedRate)
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10.dp
            }
        }
        wrapper.addView(createText(title, 13f, R.color.gray_212121, true))
        addProgressSummaryRow(
            wrapper,
            "已用 ${formatUsd(displayUsedUsd)} / ${formatUsd(limitUsd)}，剩余 ${formatUsd(remainingUsd)}",
            "已用${formatPercent(usedRate)}",
            if (isWarning) R.color.red_ff3b30 else R.color.gray_727272
        )
        wrapper.addView(createUsageProgressBar(usedRate, isWarning))
        parent.addView(wrapper)
    }

    /**
     * 添加资源包 token 进度条，只有资源包 token 有值时才会展示。
     */
    private fun addTokenProgress(parent: LinearLayout, totalTokens: Long?, consumedTokens: Long?, remainingTokens: Long?) {
        val total = calculateTokenTotal(totalTokens, consumedTokens, remainingTokens)
        val usedRate = calculateTokenUsedRate(consumedTokens, total)
        val isWarning = isProgressOverWarningThreshold(usedRate)
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dp
            }
        }
        addProgressSummaryRow(
            wrapper,
            "已用 ${formatToken(consumedTokens)} / ${formatToken(total)}，剩余 ${formatToken(remainingTokens)}",
            "已用${formatPercent(usedRate)}",
            if (isWarning) R.color.red_ff3b30 else R.color.gray_727272
        )
        wrapper.addView(createUsageProgressBar(usedRate, isWarning))
        parent.addView(wrapper)
    }

    /**
     * 百分比固定靠右展示，避免跟随左侧长文案漂移。
     */
    private fun addProgressSummaryRow(parent: LinearLayout, detailText: String, percentText: String, colorId: Int) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 2.dp
            }
        }
        row.addView(createText(detailText, 12f, colorId, false).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(createText(percentText, 12f, colorId, false).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 8.dp
            }
        })
        parent.addView(row)
    }

    /**
     * 创建圆角渐变进度条；用权重模拟进度，保证小额消耗也能保留可见宽度。
     * @param usedRate 当前已用比例
     * @param isWarning 是否超过预警线或已耗尽
     */
    private fun createUsageProgressBar(usedRate: Double?, isWarning: Boolean): View {
        val progressRate = (usedRate ?: 0.0).coerceIn(0.0, 1.0).toFloat()
        val progressWeight = if (progressRate <= 0f) {
            0f
        } else {
            (progressRate * PROGRESS_WEIGHT_TOTAL).coerceAtLeast(1f)
        }
        val remainingWeight = (PROGRESS_WEIGHT_TOTAL - progressWeight).coerceAtLeast(0f)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = createProgressBackgroundDrawable()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                8.dp
            ).apply {
                topMargin = 6.dp
            }
            if (progressWeight > 0f) {
                addView(View(this@PlanUsageInputActivity).apply {
                    background = createProgressFillDrawable(isWarning)
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        progressWeight
                    )
                })
            }
            if (remainingWeight > 0f) {
                addView(View(this@PlanUsageInputActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        remainingWeight
                    )
                })
            }
        }
    }

    /**
     * 进度条背景统一使用浅灰圆角，承托前景渐变色。
     */
    private fun createProgressBackgroundDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 4.dp.toFloat()
            setColor(getColorCompat(R.color.gray_eeeeee))
        }
    }

    /**
     * 前景填充根据预警状态切换渐变色：正常蓝青，超过 80% 或耗尽时亮红到亮橙。
     * @param isWarning 是否使用预警渐变
     */
    private fun createProgressFillDrawable(isWarning: Boolean): GradientDrawable {
        val startColor = if (isWarning) R.color.red_ff3b30 else R.color.blue_2771fa
        val endColor = if (isWarning) R.color.orange_ff9f0a else R.color.teal_200
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(getColorCompat(startColor), getColorCompat(endColor))
        ).apply {
            cornerRadius = 4.dp.toFloat()
        }
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
