package com.hss.mycodex.activity

import android.content.Context
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.hss.mycodex.R
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 说明：手动输入订阅 key 的用量查询页，key 保存到本地 SP，便于反复刷新查看额度。
 *
 * @作者 huangssh
 * @版本 1.0
 */
class PlanUsageInputActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText
    private lateinit var llActionRow: LinearLayout
    /**
     * 输入模式下从剪贴板填充 key 的按钮，与重置按钮互斥展示。
     */
    private lateinit var btnPasteKey: Button
    private lateinit var btnResetKey: Button
    private lateinit var btnRefreshPage: Button
    private lateinit var llResult: LinearLayout
    private val usdFormatter = DecimalFormat("0.##")
    private val percentFormatter = DecimalFormat("0.#")
    private val tokenFormatter = DecimalFormat("#,###")

    /**
     * 保存用户手动输入的订阅 key 和最近一次成功返回的窗口结束时间。
     */
    private val planUsagePrefs: SharedPreferences by lazy {
        getSharedPreferences(PLAN_USAGE_INPUT_PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "订阅 Key 查询"
        setContentView(createContentView())
        val savedApiKey = getSavedApiKey()
        etApiKey.setText(savedApiKey)
        if (savedApiKey.isBlank()) {
            showInputMode()
            renderEmptyPrompt()
        } else {
            showSavedKeyMode()
            loadPlanUsage(savedApiKey)
        }
    }

    /**
     * 页面直接用代码生成，保持和参考项目一样的本地工具页形态。
     */
    private fun createContentView(): View {
        val scrollView = NestedScrollView(this).apply {
            setBackgroundColor(getColorCompat(R.color.white_f5f6fa))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 16.dp)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
//        container.addView(createText("订阅 Key 查询", 24f, R.color.gray_212121, true))
        etApiKey = EditText(this).apply {
            hint = "请输入 plan- 开头的 apikey"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSelectAllOnFocus(true)
            setPadding(12.dp, 0, 12.dp, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                48.dp
            )
        }
        container.addView(etApiKey)
        llActionRow = createButtonRow()
        container.addView(llActionRow)
        llResult = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(llResult)
        scrollView.addView(container)
        return scrollView
    }

    /**
     * 输入模式展示粘贴和查询；保存 key 后切换为重置和刷新，减少无关操作干扰。
     */
    private fun createButtonRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12.dp
            }
            btnPasteKey = Button(this@PlanUsageInputActivity).apply {
                text = "粘贴"
                isAllCaps = false
                setOnClickListener { pasteApiKeyFromClipboard() }
                layoutParams = LinearLayout.LayoutParams(0, 46.dp, 1f).apply {
                    marginEnd = 6.dp
                }
            }
            btnResetKey = Button(this@PlanUsageInputActivity).apply {
                text = "重置 Key"
                isAllCaps = false
                setOnClickListener { resetApiKey() }
                layoutParams = LinearLayout.LayoutParams(0, 46.dp, 1f).apply {
                    marginEnd = 6.dp
                }
            }
            btnRefreshPage = Button(this@PlanUsageInputActivity).apply {
                text = "刷新页面"
                isAllCaps = false
                setOnClickListener { refreshWithInputKey() }
                layoutParams = LinearLayout.LayoutParams(0, 46.dp, 1f).apply {
                    marginStart = 6.dp
                }
            }
            addView(btnPasteKey)
            addView(btnResetKey)
            addView(btnRefreshPage)
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
     * 点击刷新时以输入框为准，保存到 SP 后再查询，方便下次打开自动恢复。
     */
    private fun refreshWithInputKey() {
        val apiKey = etApiKey.text?.toString()?.trim().orEmpty()
        if (apiKey.isBlank()) {
            val savedApiKey = getSavedApiKey()
            MyToastD.show(if (savedApiKey.isBlank()) "请输入 apikey" else "请输入新的 apikey")
            showInputMode()
            if (savedApiKey.isBlank()) {
                renderEmptyPrompt()
            } else {
                renderKeyEditingPrompt(savedApiKey)
            }
            return
        }
        hideKeyboard()
        saveApiKey(apiKey)
        showSavedKeyMode()
        loadPlanUsage(apiKey)
    }

    /**
     * 进入重新输入 key 的状态；旧 key 在新 key 查询前继续保留，避免误点后丢失可用配置。
     */
    private fun resetApiKey() {
        val savedApiKey = getSavedApiKey()
        etApiKey.setText("")
        showInputMode()
        if (savedApiKey.isBlank()) {
            renderEmptyPrompt()
        } else {
            renderKeyEditingPrompt(savedApiKey)
        }
        MyToastD.show("请输入新的 Key")
    }

    /**
     * 查询时输入框会隐藏，先收起软键盘避免页面状态和输入法状态不一致。
     */
    private fun hideKeyboard() {
        etApiKey.clearFocus()
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputMethodManager?.hideSoftInputFromWindow(etApiKey.windowToken, 0)
    }

    /**
     * 无本地 key 时展示输入框、粘贴和查询按钮，减少首次打开页面的操作干扰。
     */
    private fun showInputMode() {
        etApiKey.visibility = View.VISIBLE
        llActionRow.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = 12.dp
        }
        btnPasteKey.visibility = View.VISIBLE
        btnResetKey.visibility = View.GONE
        btnRefreshPage.text = "查询"
        btnRefreshPage.isEnabled = true
        btnRefreshPage.layoutParams = LinearLayout.LayoutParams(0, 46.dp, 1f).apply {
            marginStart = 6.dp
        }
    }

    /**
     * 已保存 key 时隐藏输入框，只保留重置和刷新，避免误编辑当前正在查询的 key。
     */
    private fun showSavedKeyMode() {
        etApiKey.visibility = View.GONE
        llActionRow.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = 0
        }
        btnPasteKey.visibility = View.GONE
        btnResetKey.visibility = View.VISIBLE
        btnResetKey.isEnabled = true
        btnRefreshPage.text = "刷新页面"
        btnRefreshPage.isEnabled = true
        btnRefreshPage.layoutParams = LinearLayout.LayoutParams(0, 46.dp, 1f).apply {
            marginStart = 6.dp
        }
    }

    /**
     * 如果输入了新 key，需要同步清理旧 key 的窗口结束时间缓存。
     * @param apiKey 当前输入框中的订阅 key
     */
    private fun saveApiKey(apiKey: String) {
        val oldApiKey = getSavedApiKey()
        val editor = planUsagePrefs.edit().putString(PREF_KEY_API_KEY, apiKey)
        if (oldApiKey != apiKey) {
            editor.remove(PREF_KEY_DAY_WINDOW_END_AT)
            editor.remove(PREF_KEY_WEEK_WINDOW_END_AT)
        }
        editor.apply()
    }

    private fun getSavedApiKey(): String {
        return planUsagePrefs.getString(PREF_KEY_API_KEY, null).orEmpty()
    }

    /**
     * 查询当前输入 key 的订阅用量，结果、空订阅和错误都直接显示在页面卡片里。
     * @param apiKey 已保存并用于鉴权的订阅 key
     */
    private fun loadPlanUsage(apiKey: String) {
        btnRefreshPage.isEnabled = false
        btnResetKey.isEnabled = false
        btnRefreshPage.text = "查询中..."
        showLoadingCard(apiKey)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    PlanUsageQueryResult(requestUsage(apiKey), null)
                } catch (throwable: Throwable) {
                    PlanUsageQueryResult(null, throwable.message ?: "查询失败")
                }
            }
            renderResult(apiKey, result)
            if (getSavedApiKey().isBlank()) {
                showInputMode()
            } else {
                showSavedKeyMode()
            }
            if (result.error != null) {
                MyToastD.show("订阅查询失败")
            }
        }
    }

    /**
     * 没有 key 时显示输入提示，避免空页面。
     */
    private fun renderEmptyPrompt() {
        llResult.removeAllViews()
        llResult.addView(createCard().apply {
            addView(createText("请输入 apikey 后点击查询", 14f, R.color.gray_727272, false))
        })
    }

    /**
     * 重置后展示换 key 提示；此时旧 key 仍在 SP 中，只有查询新 key 时才会被覆盖。
     * @param savedApiKey 当前仍保留在本地的订阅 key
     */
    private fun renderKeyEditingPrompt(savedApiKey: String) {
        llResult.removeAllViews()
        llResult.addView(createCard().apply {
            addView(createText("请输入新的 apikey 后点击查询", 14f, R.color.gray_727272, false))
            addView(createText("未查询前仍保留：${maskKey(savedApiKey)}", 12f, R.color.gray_999999, false).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 6.dp
                }
            })
        })
    }

    /**
     * 查询过程中先显示脱敏 key，用户能确认当前请求的是哪个 key。
     * @param apiKey 当前正在查询的订阅 key
     */
    private fun showLoadingCard(apiKey: String) {
        llResult.removeAllViews()
        llResult.addView(createCard().apply {
            renderCardHeader(this, apiKey, "重置日查询中")
            addView(createText("查询中...", 14f, R.color.gray_727272, false))
        })
    }

    /**
     * 按固定 key 页面同样的结构展示周期订阅和资源包套餐。
     * @param apiKey 本次查询使用的订阅 key
     * @param result 接口请求后的页面结果
     */
    private fun renderResult(apiKey: String, result: PlanUsageQueryResult) {
        llResult.removeAllViews()
        val card = createCard()
        val usage = result.usage
        renderCardHeader(card, apiKey, formatWeekResetHint(usage?.weekWindowEndAt))
        if (result.error != null) {
            card.addView(createText("查询失败：${result.error}", 14f, R.color.orange_fe5d36, false))
            addCachedWindowRows(card)
            llResult.addView(card)
            return
        }
        if (usage == null) {
            card.addView(createText("当前 key 无可用订阅或额度已耗尽", 14f, R.color.gray_727272, false))
            addCachedWindowRows(card)
            llResult.addView(card)
            return
        }
        addRow(card, "套餐", usage.planName ?: "--")
        addRow(card, "类型/状态", "${usage.type ?: "--"} / ${usage.status ?: "--"}")
        val hasCycleUsage = usage.hasCycleUsage()
        val hasResourceUsage = usage.hasResourceUsage()
        if (hasCycleUsage) {
            addSectionTitle(card, "周期订阅")
            addUsageProgress(card, "日额度", usage.dailyUsedUsd, usage.dailyLimitUsd, usage.dailyRemainingUsd)
            addUsageProgress(card, "周额度", usage.weeklyUsedUsd, usage.weeklyLimitUsd, usage.weeklyRemainingUsd)
            addRow(card, "日窗口结束", formatWindowEndAt(PREF_KEY_DAY_WINDOW_END_AT, usage.dayWindowEndAt))
            addRow(card, "周窗口结束", formatWindowEndAt(PREF_KEY_WEEK_WINDOW_END_AT, usage.weekWindowEndAt))
        }
        if (hasResourceUsage) {
            addSectionTitle(card, "资源包套餐")
            addTokenProgress(card, usage.totalTokens, usage.consumedTokens, usage.remainingTokens)
        }
        if (!hasResourceUsage && !hasCycleUsage) {
            card.addView(createText("暂无可展示额度", 14f, R.color.gray_727272, false))
        }
        addRow(card, "允许模型", usage.allowedModels.takeIf { it.isNotEmpty() }?.joinToString() ?: "--")
        llResult.addView(card)
    }

    /**
     * 空订阅或请求失败时仍展示上次成功缓存的窗口结束时间。
     */
    private fun addCachedWindowRows(card: LinearLayout) {
        addRow(card, "日窗口结束", formatWindowEndAt(PREF_KEY_DAY_WINDOW_END_AT, null))
        addRow(card, "周窗口结束", formatWindowEndAt(PREF_KEY_WEEK_WINDOW_END_AT, null))
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
        return PlanUsageSnapshot(
            planName = jsonObject.stringOrNull("planName"),
            type = jsonObject.intOrNull("type"),
            status = jsonObject.intOrNull("status"),
            dailyLimitUsd = jsonObject.doubleOrNull("dailyLimitUsd"),
            weeklyLimitUsd = jsonObject.doubleOrNull("weeklyLimitUsd"),
            dailyUsedUsd = jsonObject.doubleOrNull("dailyUsedUsd"),
            weeklyUsedUsd = jsonObject.doubleOrNull("weeklyUsedUsd"),
            dailyRemainingUsd = jsonObject.doubleOrNull("dailyRemainingUsd"),
            weeklyRemainingUsd = jsonObject.doubleOrNull("weeklyRemainingUsd"),
            dayWindowEndAt = jsonObject.stringOrNull("dayWindowEndAt"),
            weekWindowEndAt = jsonObject.stringOrNull("weekWindowEndAt"),
            totalTokens = jsonObject.longOrNull("totalTokens"),
            consumedTokens = jsonObject.longOrNull("consumedTokens"),
            remainingTokens = jsonObject.longOrNull("remainingTokens"),
            allowedModels = allowedModels
        )
    }

    /**
     * 每张结果卡顶部展示重置日和脱敏 key，方便确认当前查询对象。
     */
    private fun renderCardHeader(card: LinearLayout, apiKey: String, resetHint: String) {
        card.addView(createText("当前 Key  $resetHint", 18f, R.color.gray_212121, true))
        card.addView(
            createText(maskKey(apiKey), 12f, R.color.gray_999999, false).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 2.dp
                    bottomMargin = 10.dp
                }
            }
        )
    }

    /**
     * 创建通用结果卡片，复用固定 key 页面视觉。
     */
    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            background = GradientDrawable().apply {
                cornerRadius = 10.dp.toFloat()
                setColor(getColorCompat(R.color.white))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12.dp
            }
        }
    }

    private fun createText(textValue: String, textSizeSp: Float, colorId: Int, bold: Boolean): TextView {
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
    private fun addRow(parent: LinearLayout, label: String, value: String) {
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
     * 前景填充根据预警状态切换渐变色：正常蓝青，超过 80% 或耗尽时橙红。
     * @param isWarning 是否使用预警渐变
     */
    private fun createProgressFillDrawable(isWarning: Boolean): GradientDrawable {
        val startColor = if (isWarning) R.color.orange_fe5d36 else R.color.blue_2771fa
        val endColor = if (isWarning) R.color.red_ff3b30 else R.color.teal_200
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

    private fun formatWeekResetHint(weekWindowEndAt: String?): String {
        val usableWindowEndAt = resolveWindowEndAt(PREF_KEY_WEEK_WINDOW_END_AT, weekWindowEndAt)
        val date = parseWindowEndAt(usableWindowEndAt) ?: return RESET_UNKNOWN_HINT
        val calendar = Calendar.getInstance(BEIJING_TIME_ZONE).apply {
            time = date
        }
        return "每${formatWeekDay(calendar.get(Calendar.DAY_OF_WEEK))}重置"
    }

    private fun formatWindowEndAt(cacheKey: String, windowEndAt: String?): String {
        return formatBeijingTime(resolveWindowEndAt(cacheKey, windowEndAt))
    }

    /**
     * 只缓存接口返回且能解析的真实窗口时间，接口无值时回退 SP 缓存。
     */
    private fun resolveWindowEndAt(cacheKey: String, windowEndAt: String?): String? {
        val realWindowEndAt = windowEndAt?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeIf { parseWindowEndAt(it) != null }
        if (realWindowEndAt != null) {
            planUsagePrefs.edit()
                .putString(cacheKey, realWindowEndAt)
                .apply()
            return realWindowEndAt
        }
        return planUsagePrefs.getString(cacheKey, null)
    }

    /**
     * 将服务端 UTC 窗口结束时间固定展示为北京时间。
     */
    private fun formatBeijingTime(windowEndAt: String?): String {
        val date = parseWindowEndAt(windowEndAt) ?: return "--"
        return "${BEIJING_TIME_FORMAT.format(date)} 北京时间"
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

    private fun formatWeekDay(dayOfWeek: Int): String {
        return when (dayOfWeek) {
            Calendar.MONDAY -> "周一"
            Calendar.TUESDAY -> "周二"
            Calendar.WEDNESDAY -> "周三"
            Calendar.THURSDAY -> "周四"
            Calendar.FRIDAY -> "周五"
            Calendar.SATURDAY -> "周六"
            Calendar.SUNDAY -> "周日"
            else -> "周--"
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
     * 页面展示层使用的用量快照，只保留本次需要查看的额度和 token 字段。
     */
    data class PlanUsageSnapshot(
        val planName: String?,
        val type: Int?,
        val status: Int?,
        val dailyLimitUsd: Double?,
        val weeklyLimitUsd: Double?,
        val dailyUsedUsd: Double?,
        val weeklyUsedUsd: Double?,
        val dailyRemainingUsd: Double?,
        val weeklyRemainingUsd: Double?,
        val dayWindowEndAt: String?,
        val weekWindowEndAt: String?,
        val totalTokens: Long?,
        val consumedTokens: Long?,
        val remainingTokens: Long?,
        val allowedModels: List<String>
    ) {
        /**
         * 资源包套餐以 token 字段为主，只要任一 token 额度字段有有效值就展示资源包区块。
         */
        fun hasResourceUsage(): Boolean {
            return listOf(totalTokens, consumedTokens, remainingTokens).any { value ->
                value != null && value > 0L
            }
        }

        /**
         * 周期订阅以日/周美元额度为主，只要任一周期额度字段有有效值就展示周期区块。
         */
        fun hasCycleUsage(): Boolean {
            return listOf(dailyLimitUsd, weeklyLimitUsd, dailyUsedUsd, weeklyUsedUsd, dailyRemainingUsd, weeklyRemainingUsd)
                .any { value -> value != null && value > 0.0 }
        }
    }

    /**
     * 单 key 查询结果，允许成功空订阅和失败状态共用同一套渲染入口。
     */
    data class PlanUsageQueryResult(
        val usage: PlanUsageSnapshot?,
        val error: String?
    )

    companion object {
        private const val USAGE_ENDPOINT = "https://api.routin.ai/plan/v1/usage"
        private const val PROGRESS_WARNING_THRESHOLD = 0.8
        private const val PROGRESS_WEIGHT_TOTAL = 1000f
        private const val RESET_UNKNOWN_HINT = "重置日未知"
        private const val PLAN_USAGE_INPUT_PREFS_NAME = "plan_usage_input_cache"
        private const val PREF_KEY_API_KEY = "api_key"
        private const val PREF_KEY_DAY_WINDOW_END_AT = "day_window_end_at"
        private const val PREF_KEY_WEEK_WINDOW_END_AT = "week_window_end_at"
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
