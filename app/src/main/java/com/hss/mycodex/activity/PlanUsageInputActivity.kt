package com.hss.mycodex.activity

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
import android.util.Log
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hss.mycodex.R
import com.hss.mycodex.adapter.PlanUsageKeyAdapter
import com.hss.mycodex.model.PlanUsageSnapshot
import com.hss.mycodex.model.SavedPlanKey
import com.hss.mycodex.store.PlanUsageKeyStore
import com.hss.mycodex.widget.MyToastD
import com.hss.mycodex.widget.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

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
     * 本地存储负责迁移旧单 Key 数据，并保存多 Key 的卡片状态和查询缓存。
     */
    private val planUsageKeyStore by lazy { PlanUsageKeyStore(this) }

    /**
     * 内存中的全部 Key 是页面唯一数据源；展示时再按置顶状态和创建时间排序。
     */
    private val savedPlanKeys = mutableListOf<SavedPlanKey>()

    /**
     * 请求进度和错误提示只服务于当前页面会话，避免短暂的刷新状态在下次启动时误导用户。
     */
    private val refreshingKeyIds = mutableSetOf<String>()
    private val latestErrorByKeyId = mutableMapOf<String, String>()
    private lateinit var planUsageKeyAdapter: PlanUsageKeyAdapter
    private var isAddKeyPanelVisible = false
    private var isAddingKey = false
    private var isRefreshingAll = false
    private var refreshCurrentIndex = 0
    private var refreshTotalCount = 0
    private var refreshStatusText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "订阅 Key 查询"
        savedPlanKeys.addAll(planUsageKeyStore.loadKeys())
        isAddKeyPanelVisible = savedPlanKeys.isEmpty()
        setContentView(createContentView())
        renderPage()
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
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
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
     * 刷新页面上的数量、添加状态和列表缓存；列表状态始终从同一份 Key 数据派生。
     */
    private fun renderPage() {
        tvKeyCount.text = "我的 Key（${savedPlanKeys.size}）"
        tvRefreshStatus.text = when {
            isRefreshingAll -> "刷新中 ${refreshCurrentIndex}/${refreshTotalCount}"
            !refreshStatusText.isNullOrBlank() -> refreshStatusText
            else -> ""
        }
        tvRefreshStatus.visibility = if (tvRefreshStatus.text.isNullOrBlank()) View.GONE else View.VISIBLE
        btnAddKey.isEnabled = !isRefreshingAll
        btnAddKey.text = if (isAddKeyPanelVisible) "收起" else "添加 Key"
        btnRefreshAll.isEnabled = savedPlanKeys.isNotEmpty() && !isRefreshingAll
        btnRefreshAll.text = if (isRefreshingAll) "刷新中..." else "刷新全部"
        btnQueryAndAdd.isEnabled = !isAddingKey && !isRefreshingAll
        btnQueryAndAdd.text = if (isAddingKey) "查询中..." else "查询并添加"
        btnPasteKey.isEnabled = !isAddingKey && !isRefreshingAll
        llAddKeyPanel.visibility = if (isAddKeyPanelVisible) View.VISIBLE else View.GONE
        tvEmptyHint.visibility = if (savedPlanKeys.isEmpty()) View.VISIBLE else View.GONE
        rvPlanKeys.visibility = if (savedPlanKeys.isEmpty()) View.GONE else View.VISIBLE
        planUsageKeyAdapter.submit(sortedPlanKeys(), refreshingKeyIds)
    }

    /**
     * 添加入口可展开或收起，收起时保留用户已输入内容，避免误点后丢失尚未验证的新 Key。
     */
    private fun toggleAddKeyPanel() {
        if (isRefreshingAll) {
            return
        }
        isAddKeyPanelVisible = !isAddKeyPanelVisible
        if (!isAddKeyPanelVisible) {
            hideKeyboard()
        }
        renderPage()
        if (isAddKeyPanelVisible) {
            etApiKey.requestFocus()
        }
    }

    /**
     * 仅在接口可正常返回时新增 Key，失败时保留用户输入，便于修正后再次查询。
     */
    private fun queryAndAddPlanKey() {
        if (isAddingKey || isRefreshingAll) {
            return
        }
        val apiKey = etApiKey.text?.toString()?.trim().orEmpty()
        if (apiKey.isBlank()) {
            MyToastD.show("请输入 apikey")
            return
        }
        val duplicatedKey = savedPlanKeys.firstOrNull { it.apiKey == apiKey }
        if (duplicatedKey != null) {
            MyToastD.show("该 Key 已添加")
            isAddKeyPanelVisible = false
            hideKeyboard()
            renderPage()
            scrollToPlanKey(duplicatedKey.id)
            return
        }
        isAddingKey = true
        renderPage()
        hideKeyboard()
        lifecycleScope.launch {
            val result = queryPlanUsage(apiKey)
            isAddingKey = false
            if (result.error != null) {
                renderPage()
                MyToastD.show("订阅查询失败：${result.error}")
                return@launch
            }
            val now = System.currentTimeMillis()
            val name = etKeyName.text?.toString()?.trim().orEmpty().ifBlank {
                "Key ${savedPlanKeys.size + 1}"
            }
            savedPlanKeys.add(
                SavedPlanKey(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    apiKey = apiKey,
                    createdAt = now,
                    sortOrder = nextPlanKeySortOrder(),
                    lastUpdatedAt = now,
                    cachedStartAt = result.usage?.startAt,
                    cachedEndAt = result.usage?.endAt,
                    cachedDayWindowStartAt = result.usage?.dayWindowStartAt,
                    cachedDayWindowEndAt = result.usage?.dayWindowEndAt,
                    cachedWeekWindowStartAt = result.usage?.weekWindowStartAt,
                    cachedWeekWindowEndAt = result.usage?.weekWindowEndAt,
                    cachedUsage = result.usage
                )
            )
            planUsageKeyStore.saveKeys(savedPlanKeys)
            etKeyName.setText("")
            etApiKey.setText("")
            isAddKeyPanelVisible = false
            refreshStatusText = null
            renderPage()
            scrollToPlanKey(savedPlanKeys.last().id)
            MyToastD.show("已添加 $name")
        }
    }

    /**
     * 全部刷新按当前展示顺序串行发起，避免多个 Key 同时请求导致接口压力或状态错位。
     */
    private fun refreshAllPlanKeys() {
        if (savedPlanKeys.isEmpty() || isRefreshingAll) {
            return
        }
        hideKeyboard()
        isAddKeyPanelVisible = false
        isRefreshingAll = true
        refreshStatusText = null
        val refreshQueue = sortedPlanKeys()
        refreshTotalCount = refreshQueue.size
        refreshCurrentIndex = 0
        renderPage()
        lifecycleScope.launch {
            refreshQueue.forEachIndexed { index, planKey ->
                refreshCurrentIndex = index + 1
                refreshingKeyIds.add(planKey.id)
                renderPage()
                val result = queryPlanUsage(planKey.apiKey)
                refreshingKeyIds.remove(planKey.id)
                applyRefreshResult(planKey.id, result)
                renderPage()
            }
            isRefreshingAll = false
            refreshStatusText = "已刷新 ${refreshTotalCount} 项"
            renderPage()
        }
    }

    /**
     * 成功刷新才覆盖卡片缓存；请求失败只记录当次提示，旧额度仍然可以继续查看。
     * @param keyId 本次请求对应的 Key ID
     * @param result 接口查询结果
     */
    private fun applyRefreshResult(keyId: String, result: PlanUsageQueryResult) {
        if (result.error != null) {
            latestErrorByKeyId[keyId] = result.error
            return
        }
        latestErrorByKeyId.remove(keyId)
        updatePlanKey(keyId) { planKey ->
            planKey.copy(
                lastUpdatedAt = System.currentTimeMillis(),
                cachedStartAt = result.usage?.startAt ?: planKey.cachedStartAt,
                cachedEndAt = result.usage?.endAt ?: planKey.cachedEndAt,
                cachedDayWindowStartAt = result.usage?.dayWindowStartAt ?: planKey.cachedDayWindowStartAt,
                cachedDayWindowEndAt = result.usage?.dayWindowEndAt ?: planKey.cachedDayWindowEndAt,
                cachedWeekWindowStartAt = result.usage?.weekWindowStartAt ?: planKey.cachedWeekWindowStartAt,
                cachedWeekWindowEndAt = result.usage?.weekWindowEndAt ?: planKey.cachedWeekWindowEndAt,
                cachedUsage = result.usage
            )
        }
    }

    /**
     * 卡片按用户保存的顺序号展示，排序号相同时再按添加时间兜底，避免异常数据导致顺序不稳定。
     */
    private fun sortedPlanKeys(): List<SavedPlanKey> {
        return savedPlanKeys.sortedWith(
            compareBy<SavedPlanKey> { it.sortOrder }
                .thenBy { it.createdAt }
        )
    }

    /**
     * 新添加的 Key 始终使用当前最大排序号，保证其显示在列表末尾。
     */
    private fun nextPlanKeySortOrder(): Int {
        return (savedPlanKeys.maxOfOrNull { it.sortOrder } ?: -1) + 1
    }

    /**
     * 更新一项后立即写入 SP，保证展开、排序、命名和刷新缓存关闭 App 后仍能恢复。
     * @param keyId 需要更新的 Key ID
     * @param transform 基于旧条目生成新条目的变换
     */
    private fun updatePlanKey(keyId: String, transform: (SavedPlanKey) -> SavedPlanKey) {
        val index = savedPlanKeys.indexOfFirst { it.id == keyId }
        if (index < 0) {
            return
        }
        savedPlanKeys[index] = transform(savedPlanKeys[index])
        planUsageKeyStore.saveKeys(savedPlanKeys)
    }

    /**
     * 与目标相邻卡片交换排序号，实现一次只移动一位且保留其他卡片顺序。
     * @param keyId 需要移动的 Key ID
     * @param moveOffset 上移为 -1，下移为 1
     */
    private fun movePlanKeyByOne(keyId: String, moveOffset: Int) {
        val orderedKeys = sortedPlanKeys()
        val currentPosition = orderedKeys.indexOfFirst { it.id == keyId }
        val targetPosition = currentPosition + moveOffset
        if (currentPosition < 0 || targetPosition !in orderedKeys.indices) {
            return
        }
        val currentKey = orderedKeys[currentPosition]
        val targetKey = orderedKeys[targetPosition]
        val currentIndex = savedPlanKeys.indexOfFirst { it.id == currentKey.id }
        val targetIndex = savedPlanKeys.indexOfFirst { it.id == targetKey.id }
        if (currentIndex < 0 || targetIndex < 0) {
            return
        }
        savedPlanKeys[currentIndex] = currentKey.copy(sortOrder = targetKey.sortOrder)
        savedPlanKeys[targetIndex] = targetKey.copy(sortOrder = currentKey.sortOrder)
        planUsageKeyStore.saveKeys(savedPlanKeys)
        renderPage()
    }

    /**
     * 将重复 Key 或刚添加的新 Key 定位到当前排序后的卡片位置，减少用户再次查找的成本。
     */
    private fun scrollToPlanKey(keyId: String) {
        val index = sortedPlanKeys().indexOfFirst { it.id == keyId }
        if (index >= 0) {
            rvPlanKeys.post { rvPlanKeys.smoothScrollToPosition(index) }
        }
    }

    /**
     * 卡片容器由 RecyclerView 复用，页面层根据持久状态补齐完整的展示内容和交互入口。
     */
    private fun renderPlanKeyCard(card: LinearLayout, planKey: SavedPlanKey, isRefreshing: Boolean) {
        card.removeAllViews()
        val header = LinearLayout(this).apply {
            // 操作区顶部对齐标题，避免标题高度变化时按钮纵向漂移。
            gravity = Gravity.TOP
            orientation = LinearLayout.HORIZONTAL
            isClickable = true
            setOnClickListener {
                updatePlanKey(planKey.id) { it.copy(isExpanded = !it.isExpanded) }
                renderPage()
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
                updatePlanKey(planKey.id) { it.copy(isExpanded = !it.isExpanded) }
                renderPage()
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

        latestErrorByKeyId[planKey.id]?.let { error ->
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
        if (isRefreshingAll) {
            MyToastD.show("正在刷新全部 Key")
            return
        }
        val currentPosition = sortedPlanKeys().indexOfFirst { it.id == planKey.id }
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_MOVE_UP, 0, "上移一位").isEnabled = currentPosition > 0
            menu.add(0, MENU_MOVE_DOWN, 1, "下移一位").isEnabled = currentPosition in 0 until savedPlanKeys.lastIndex
            menu.add(0, MENU_RENAME, 2, "重命名")
            menu.add(0, MENU_DELETE, 3, "删除")
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    MENU_MOVE_UP -> movePlanKeyByOne(planKey.id, MOVE_OFFSET_UP)
                    MENU_MOVE_DOWN -> movePlanKeyByOne(planKey.id, MOVE_OFFSET_DOWN)
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
                updatePlanKey(planKey.id) { it.copy(name = newName) }
                renderPage()
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
                savedPlanKeys.removeAll { it.id == planKey.id }
                latestErrorByKeyId.remove(planKey.id)
                planUsageKeyStore.saveKeys(savedPlanKeys)
                if (savedPlanKeys.isEmpty()) {
                    isAddKeyPanelVisible = true
                }
                renderPage()
            }
            .show()
    }

    /**
     * 查询在 IO 线程执行，所有入口共用相同异常语义，保证添加与整体刷新展示一致。
     */
    private suspend fun queryPlanUsage(apiKey: String): PlanUsageQueryResult {
        return withContext(Dispatchers.IO) {
            try {
                PlanUsageQueryResult(requestUsage(apiKey), null)
            } catch (throwable: Throwable) {
                // 保留完整证书与网络异常链，便于通过 Logcat 定位客户端 TLS 失败原因。
                Log.e(PLAN_USAGE_LOG_TAG, "订阅额度查询失败", throwable)
                PlanUsageQueryResult(null, throwable.message ?: "查询失败")
            }
        }
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
     * 发起真实接口请求；接口通过 Authorization Bearer 识别订阅主体。
     * @param apiKey 用户输入并保存到本地的订阅 key
     */
    private fun requestUsage(apiKey: String): PlanUsageSnapshot? {
        val connection = (URL(USAGE_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw IllegalStateException("鉴权失败 invalid_api_key")
            }
            if (responseCode !in 200..299) {
                throw IllegalStateException("HTTP $responseCode ${responseText.take(120)}")
            }
            val body = responseText.trim()
            if (body.isEmpty() || body == "null") {
                return null
            }
            return parseUsage(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 将接口 JSON 解析成本页展示所需字段，接口额外字段不会影响当前页面。
     * @param jsonObject 接口返回的订阅用量 JSON
     */
    private fun parseUsage(jsonObject: JSONObject): PlanUsageSnapshot {
        val allowedModels = jsonObject.optJSONArray("allowedModels")?.let { jsonArray ->
            (0 until jsonArray.length()).mapNotNull { index ->
                jsonArray.optString(index).takeIf { it.isNotBlank() }
            }
        }.orEmpty()
        val allowedGroups = jsonObject.optJSONArray("allowedGroups")?.let { jsonArray ->
            (0 until jsonArray.length()).mapNotNull { index ->
                jsonArray.optString(index).takeIf { it.isNotBlank() }
            }
        }.orEmpty()
        return PlanUsageSnapshot(
            planName = jsonObject.stringOrNull("planName"),
            type = jsonObject.intOrNull("type"),
            status = jsonObject.intOrNull("status"),
            startAt = jsonObject.stringOrNull("startAt"),
            endAt = jsonObject.stringOrNull("endAt"),
            dailyLimitUsd = jsonObject.doubleOrNull("dailyLimitUsd"),
            weeklyLimitUsd = jsonObject.doubleOrNull("weeklyLimitUsd"),
            dailyUsedUsd = jsonObject.doubleOrNull("dailyUsedUsd"),
            weeklyUsedUsd = jsonObject.doubleOrNull("weeklyUsedUsd"),
            dailyRemainingUsd = jsonObject.doubleOrNull("dailyRemainingUsd"),
            weeklyRemainingUsd = jsonObject.doubleOrNull("weeklyRemainingUsd"),
            dayWindowStartAt = jsonObject.stringOrNull("dayWindowStartAt"),
            dayWindowEndAt = jsonObject.stringOrNull("dayWindowEndAt"),
            weekWindowStartAt = jsonObject.stringOrNull("weekWindowStartAt"),
            weekWindowEndAt = jsonObject.stringOrNull("weekWindowEndAt"),
            totalTokens = jsonObject.longOrNull("totalTokens"),
            consumedTokens = jsonObject.longOrNull("consumedTokens"),
            remainingTokens = jsonObject.longOrNull("remainingTokens"),
            allowedModels = allowedModels,
            allowedGroups = allowedGroups,
            groupNames = jsonObject.stringMapOrEmpty("groupNames"),
            groupMultipliers = jsonObject.doubleMapOrEmpty("groupMultipliers")
        )
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
        val greenRanges = mutableListOf<Pair<Int, Int>>()
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
            if (isLowerThanDefaultGroupMultiplier(groupId, groupName, multiplierValue)) {
                greenRanges.add(start to end)
            }
        }
        return SpannableString(textBuilder).apply {
            greenRanges.forEach { (start, end) ->
                setSpan(
                    ForegroundColorSpan(getColorCompat(R.color.green_34c759)),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    /**
     * 分组倍率低于默认基线时返回 true，用绿色提示用户当前分组更省。
     * @param groupId 服务端返回的分组 ID
     * @param groupName 服务端返回的分组名称
     * @param multiplier 当前 key 对应的分组倍率
     */
    private fun isLowerThanDefaultGroupMultiplier(groupId: String, groupName: String, multiplier: Double?): Boolean {
        val defaultMultiplier = resolveDefaultGroupMultiplier(groupId, groupName) ?: return false
        return multiplier != null && multiplier < defaultMultiplier
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

    private fun JSONObject.stringOrNull(name: String): String? {
        return if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
    }

    private fun JSONObject.intOrNull(name: String): Int? {
        return if (has(name) && !isNull(name)) optInt(name) else null
    }

    private fun JSONObject.longOrNull(name: String): Long? {
        return optLong(name, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
    }

    private fun JSONObject.doubleOrNull(name: String): Double? {
        return optDouble(name, Double.NaN).takeIf { !it.isNaN() }
    }

    /**
     * 将接口返回的 ID 到名称对象转为 Map，避免页面直接依赖 JSONObject。
     * @param name JSON 对象字段名
     */
    private fun JSONObject.stringMapOrEmpty(name: String): Map<String, String> {
        val mapObject = optJSONObject(name) ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = mapObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            mapObject.stringOrNull(key)?.let { value ->
                result[key] = value
            }
        }
        return result
    }

    /**
     * 将接口返回的 ID 到倍率对象转为 Map，保留分组倍率的原始数值。
     * @param name JSON 对象字段名
     */
    private fun JSONObject.doubleMapOrEmpty(name: String): Map<String, Double> {
        val mapObject = optJSONObject(name) ?: return emptyMap()
        val result = linkedMapOf<String, Double>()
        val keys = mapObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            mapObject.doubleOrNull(key)?.let { value ->
                result[key] = value
            }
        }
        return result
    }

    /**
     * 单 key 查询结果，允许成功空订阅和失败状态共用同一套渲染入口。
     */
    data class PlanUsageQueryResult(
        val usage: PlanUsageSnapshot?,
        val error: String?
    )

    companion object {
        /**
         * 订阅额度请求的 Logcat Tag，不包含 Key 等鉴权信息。
         */
        private const val PLAN_USAGE_LOG_TAG = "PlanUsageQuery"
        private const val USAGE_ENDPOINT = "https://api.routin.ai/plan/v1/usage"
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
