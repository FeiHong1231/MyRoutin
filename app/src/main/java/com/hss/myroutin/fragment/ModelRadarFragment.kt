package com.hss.myroutin.fragment

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hss.myroutin.R
import com.hss.myroutin.adapter.ModelRadarAdapter
import com.hss.myroutin.adapter.ModelRadarEfficiencyAdapter
import com.hss.myroutin.adapter.ModelRadarRecommendationAdapter
import com.hss.myroutin.databinding.DialogModelRadarEfficiencyBinding
import com.hss.myroutin.databinding.FragmentModelRadarBinding
import com.hss.myroutin.logic.PlanUsageFormatter
import com.hss.myroutin.model.ModelRadarEfficiency
import com.hss.myroutin.model.ModelRadarSnapshot
import com.hss.myroutin.viewmodel.ModelRadarUiEvent
import com.hss.myroutin.viewmodel.ModelRadarUiState
import com.hss.myroutin.viewmodel.ModelRadarViewModel
import com.hss.myroutin.widget.MyToastD
import com.hss.myroutin.widget.dp
import kotlinx.coroutines.launch

/**
 * 说明：模型一级页，展示 CodexRadar 公开聚合数据、场景推荐与手动刷新状态。
 *
 * @作者 huangssh
 * @版本 3.0
 */
class ModelRadarFragment : Fragment() {

    /** 雷达列表 View 只在当前 Fragment View 周期内有效。 */
    private var _binding: FragmentModelRadarBinding? = null
    private val binding: FragmentModelRadarBinding
        get() = requireNotNull(_binding)

    /** 场景推荐和模型详情分层展示，避免单个 Adapter 混合两种信息类型。 */
    private val recommendationAdapter = ModelRadarRecommendationAdapter()
    private val modelAdapter = ModelRadarAdapter()
    /** 智力效率按模型/档位逐张展示，适配官网宽屏矩阵在手机上的纵向阅读。 */
    private val efficiencyAdapter = ModelRadarEfficiencyAdapter()
    /** 当前效率页筛选条件只影响展示，不改变缓存中的完整快照。 */
    private var selectedEfficiencyModelId: String? = null
    private var selectedEfficiencyEffort: String? = null
    /** 智力效率默认直接展示跨模型 IQ 排行，也保留原有模型分组浏览方式。 */
    private var selectedEfficiencyViewMode = EFFICIENCY_VIEW_MODE_RANKING
    private var efficiencyFilterSignature: String? = null
    private var allEfficiencyPoints: List<ModelRadarEfficiency> = emptyList()

    /** 网络、聚合和缓存由 Fragment 级 ViewModel 管理，导航切换不重复请求。 */
    private val viewModel by lazy {
        ViewModelProvider(this).get(ModelRadarViewModel::class.java)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentModelRadarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializePage()
        observeViewModel()
    }

    /** 初始化雷达列表和点击事件，刷新过程中由状态暂时禁用按钮。 */
    private fun initializePage() {
        binding.rvRecommendations.adapter = recommendationAdapter
        binding.rvRadarModels.adapter = modelAdapter
        binding.rvEfficiency.adapter = efficiencyAdapter
        binding.rvRecommendations.itemAnimator = null
        binding.rvRadarModels.itemAnimator = null
        binding.rvEfficiency.itemAnimator = null
        binding.swipeRefreshModelRadar.setColorSchemeResources(R.color.plan_usage_brand_primary)
        binding.swipeRefreshModelRadar.setOnRefreshListener { viewModel.refresh() }
        binding.swipeRefreshEfficiency.setColorSchemeResources(R.color.plan_usage_brand_primary)
        binding.swipeRefreshEfficiency.setOnRefreshListener { viewModel.refresh() }
        /** 横向推荐卡之间保留 8dp，末项保留 16dp 尾部空间以维持列表边界。 */
        binding.rvRecommendations.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val position = parent.getChildAdapterPosition(view)
                if (position != RecyclerView.NO_POSITION) {
                    outRect.right = if (position == state.itemCount - 1) 16.dp else 8.dp
                }
            }
        })
        efficiencyAdapter.setOnPointClickListener(::showEfficiencyDetails)
        binding.rgModelRadarTabs.setOnCheckedChangeListener { _, checkedId ->
            val isEfficiency = checkedId == R.id.rbIntelligenceEfficiency
            binding.pageSwitcher.displayedChild = if (isEfficiency) 1 else 0
        }
        binding.btnRefreshRadar.setOnClickListener { viewModel.refresh() }
        binding.btnRefreshEfficiency.setOnClickListener { viewModel.refresh() }
        binding.tvRadarSource.setOnClickListener { openRadarSource() }
        binding.tvEfficiencySource.setOnClickListener { openRadarSource() }
        // 顶部默认选中智力效率，ViewFlipper 的第一子页仍是模型雷达，因此映射到第二页。
        binding.pageSwitcher.displayedChild = 1
    }

    /** 页面可见时渲染缓存和刷新状态，切换到其他导航后停止更新 View。 */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.events.collect(::handleUiEvent) }
            }
        }
    }

    /** 消费刷新失败等一次性页面反馈，不将错误信息常驻在页面内容中。 */
    private fun handleUiEvent(event: ModelRadarUiEvent) {
        when (event) {
            is ModelRadarUiEvent.ShowToast -> MyToastD.show(event.message)
        }
    }

    /**
     * 一次性映射完整雷达状态，缓存刷新时保留已有列表避免闪烁。
     * @param state 当前模型雷达页面状态
     */
    private fun render(state: ModelRadarUiState) {
        val snapshot = state.snapshot
        val hasRecommendations = snapshot?.recommendations?.isNotEmpty() == true
        val hasModels = snapshot?.models?.isNotEmpty() == true
        val hasEfficiency = snapshot?.efficiencyPoints?.isNotEmpty() == true
        recommendationAdapter.submitList(snapshot?.recommendations.orEmpty())
        modelAdapter.submitList(snapshot?.models.orEmpty())

        binding.swipeRefreshModelRadar.isRefreshing = state.isRefreshing
        binding.swipeRefreshEfficiency.isRefreshing = state.isRefreshing
        binding.pbRadarLoading.isVisible = state.isLoading && !state.isRefreshing
        binding.tvRadarStatus.isVisible = false
        binding.tvRadarEmpty.isVisible = snapshot == null && !state.isLoading && !state.isLoadFailed
        binding.tvRecommendationTitle.isVisible = hasRecommendations
        binding.rvRecommendations.isVisible = hasRecommendations
        binding.tvModelOverviewTitle.isVisible = hasModels
        binding.tvModelOverviewHint.isVisible = hasModels
        binding.rvRadarModels.isVisible = hasModels
        binding.btnRefreshRadar.isEnabled = !state.isRefreshing
        binding.btnRefreshRadar.text = getString(
            if (state.isRefreshing) R.string.plan_usage_refreshing else R.string.action_refresh
        )
        binding.tvUpdatedAt.text = snapshot?.let(::formatUpdatedAt)
            ?: getString(R.string.model_radar_waiting_update)

        // 智力效率和模型雷达共用同一份快照，切换页签只改变展示容器，不重复请求数据。
        binding.pbEfficiencyLoading.isVisible = state.isLoading && !state.isRefreshing
        binding.tvEfficiencyStatus.isVisible = false
        binding.tvEfficiencyEmpty.isVisible = !state.isLoading && !hasEfficiency && !state.isLoadFailed
        binding.llEfficiencyTitleRow.isVisible = hasEfficiency
        binding.tvEfficiencyMeta.isVisible = hasEfficiency
        binding.tvEfficiencyMeta.text = snapshot?.recentRuns24h?.let { runs24h ->
            getString(R.string.model_radar_efficiency_meta, runs24h)
        }.orEmpty()
        val efficiencyPoints = snapshot?.efficiencyPoints.orEmpty()
        allEfficiencyPoints = efficiencyPoints
        setupEfficiencyFilters(efficiencyPoints)
        renderEfficiencyPoints(efficiencyPoints, showEmpty = !state.isLoading)
        binding.llEfficiencyFilters.isVisible = hasEfficiency
        binding.btnRefreshEfficiency.isEnabled = !state.isRefreshing
        binding.btnRefreshEfficiency.text = getString(
            if (state.isRefreshing) R.string.plan_usage_refreshing else R.string.action_refresh
        )
        binding.tvEfficiencyUpdatedAt.text = snapshot?.let(::formatUpdatedAt)
            ?: getString(R.string.model_radar_waiting_update)
    }

    /**
     * 优先展示站点数据时间，格式异常时回退到本机成功拉取时间。
     * @param snapshot 当前页面聚合快照
     * @return 可直接显示的北京时间文案
     */
    private fun formatUpdatedAt(snapshot: ModelRadarSnapshot): String {
        val sourceTime = snapshot.sourceUpdatedAt
        val displayTime = if (PlanUsageFormatter.parseServerTimeMillis(sourceTime) != null) {
            PlanUsageFormatter.formatBeijingTime(sourceTime)
        } else {
            PlanUsageFormatter.formatLocalTime(snapshot.fetchedAt)
        }
        return getString(R.string.model_radar_updated_at, displayTime)
    }

    /** 使用系统浏览器打开数据来源，无可处理应用时给出轻提示。 */
    private fun openRadarSource() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(CODEX_RADAR_URL))
        runCatching { startActivity(intent) }
            .onFailure { MyToastD.show(getString(R.string.model_radar_open_source_failed)) }
    }

    /**
     * 根据当前快照创建浏览模式和横向筛选入口；控件只在数据变化时重建，避免刷新状态造成跳动。
     * @param points 当前快照中的所有效率档位
     */
    private fun setupEfficiencyFilters(points: List<ModelRadarEfficiency>) {
        val modelOptions = points
            .distinctBy(ModelRadarEfficiency::modelId)
            .map { it.modelId to formatEfficiencyModelFilterName(it.modelId) }
        val effortOptions = points
            .map(ModelRadarEfficiency::effort)
            .distinct()
            .sortedWith(compareBy { EFFORT_ORDER.indexOf(it).coerceAtLeast(0) })
            .map { it to PlanUsageFormatter.formatEffortLabel(it) }
        val signature = buildString {
            append(modelOptions.joinToString { it.first })
            append('|')
            append(effortOptions.joinToString { it.first })
        }
        // 筛选控件属于 Fragment View 生命周期，视图重建后即使签名未变也必须重新填充。
        if (
            signature == efficiencyFilterSignature &&
            binding.llEfficiencyViewModes.childCount > 0 &&
            binding.llEfficiencyModelFilters.childCount > 0 &&
            binding.llEfficiencyEffortFilters.childCount > 0
        ) {
            return
        }
        efficiencyFilterSignature = signature
        addEfficiencyFilterOptions(
            container = binding.llEfficiencyViewModes,
            options = listOf(
                EFFICIENCY_VIEW_MODE_RANKING to
                    getString(R.string.model_radar_efficiency_view_ranking),
                EFFICIENCY_VIEW_MODE_GROUPED to
                    getString(R.string.model_radar_efficiency_view_grouped)
            ),
            selectedValue = selectedEfficiencyViewMode,
            onSelected = { viewMode ->
                selectedEfficiencyViewMode = viewMode ?: EFFICIENCY_VIEW_MODE_RANKING
                renderEfficiencyPoints(allEfficiencyPoints)
            }
        )
        if (selectedEfficiencyModelId !in modelOptions.map { it.first }) {
            selectedEfficiencyModelId = null
        }
        if (selectedEfficiencyEffort !in effortOptions.map { it.first }) {
            selectedEfficiencyEffort = null
        }
        addEfficiencyFilterOptions(
            container = binding.llEfficiencyModelFilters,
            options = listOf(null to getString(R.string.model_radar_efficiency_filter_all)) + modelOptions,
            selectedValue = selectedEfficiencyModelId,
            onSelected = { modelId ->
                selectedEfficiencyModelId = modelId
                modelId?.let(efficiencyAdapter::expandModel)
                renderEfficiencyPoints(allEfficiencyPoints)
            }
        )
        addEfficiencyFilterOptions(
            container = binding.llEfficiencyEffortFilters,
            options = listOf(null to getString(R.string.model_radar_efficiency_filter_all)) + effortOptions,
            selectedValue = selectedEfficiencyEffort,
            onSelected = { effort ->
                selectedEfficiencyEffort = effort
                renderEfficiencyPoints(allEfficiencyPoints)
            }
        )
    }

    /** 将筛选后的效率点按当前浏览模式交给排行或分组适配器。 */
    private fun renderEfficiencyPoints(
        points: List<ModelRadarEfficiency>,
        showEmpty: Boolean = true
    ) {
        val filteredPoints = points.filter { point ->
            (selectedEfficiencyModelId == null || point.modelId == selectedEfficiencyModelId) &&
                (selectedEfficiencyEffort == null || point.effort == selectedEfficiencyEffort)
        }
        val rankingPoints = if (selectedEfficiencyViewMode == EFFICIENCY_VIEW_MODE_RANKING) {
            filteredPoints.sortedWith(EFFICIENCY_RANKING_COMPARATOR)
        } else {
            filteredPoints
        }
        efficiencyAdapter.submitPoints(
            values = rankingPoints,
            showRanking = selectedEfficiencyViewMode == EFFICIENCY_VIEW_MODE_RANKING
        )
        binding.tvEfficiencyEmpty.isVisible = showEmpty && filteredPoints.isEmpty()
        binding.rvEfficiency.isVisible = filteredPoints.isNotEmpty()
    }

    /** 创建可横向滚动的轻量筛选按钮，沿用模型页已有页签背景和浅深色语义资源。 */
    private fun addEfficiencyFilterOptions(
        container: LinearLayout,
        options: List<Pair<String?, String>>,
        selectedValue: String?,
        onSelected: (String?) -> Unit
    ) {
        container.removeAllViews()
        val textColors = requireNotNull(
            ContextCompat.getColorStateList(requireContext(), R.color.model_radar_tab_text)
        )
        options.forEach { (value, label) ->
            val optionView = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    32.dp
                ).apply {
                    marginEnd = 6.dp
                }
                background = ContextCompat.getDrawable(context, R.drawable.bg_model_radar_tab)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                isSelected = value == selectedValue
                minWidth = 44.dp
                setPadding(10.dp, 0, 10.dp, 0)
                setTextColor(textColors)
                text = label
                textSize = 12f
                setOnClickListener {
                    for (index in 0 until container.childCount) {
                        container.getChildAt(index).isSelected = container.getChildAt(index) === this
                    }
                    onSelected(value)
                }
            }
            container.addView(optionView)
        }
    }

    /** 详情使用底部面板承载完整指标，避免主列表同时出现过多小字号字段。 */
    private fun showEfficiencyDetails(point: ModelRadarEfficiency) {
        val detailBinding = DialogModelRadarEfficiencyBinding.inflate(layoutInflater)
        detailBinding.tvDetailModelName.text = point.modelName
        detailBinding.tvDetailEffort.text = PlanUsageFormatter.formatEffortLabel(point.effort)
        detailBinding.tvDetailIq.text = getString(
            R.string.model_radar_efficiency_detail_iq,
            PlanUsageFormatter.formatDecimal(point.iq)
        )
        detailBinding.tvDetailCost.text = PlanUsageFormatter.formatUsd(point.averageCostUsd)
        detailBinding.tvDetailDuration.text = point.averageDurationMinutes
            ?.let(PlanUsageFormatter::formatDecimal)
            ?.let { getString(R.string.model_radar_duration_minutes, it) }
            ?: getString(R.string.plan_usage_value_unavailable)
        detailBinding.tvDetailRuns.text = point.totalRuns.toString()
        detailBinding.tvDetailSteps.text = getString(
            R.string.model_radar_efficiency_detail_steps,
            formatEfficiencyMetric(point.averageAgentSteps)
        )
        detailBinding.tvDetailTokens.text = getString(
            R.string.model_radar_efficiency_detail_tokens,
            PlanUsageFormatter.formatTokenInMillions(point.averageTotalTokens?.toLong())
        )
        detailBinding.tvDetailCache.text = getString(
            R.string.model_radar_efficiency_detail_cache,
            PlanUsageFormatter.formatPercent(point.cacheHitRate)
        )
        BottomSheetDialog(requireContext()).apply {
            setContentView(detailBinding.root)
            show()
        }
    }

    /** 效率指标空值统一显示占位符，避免详情面板误把缺失数据显示成 0。 */
    private fun formatEfficiencyMetric(value: Double?): String {
        return value?.let(PlanUsageFormatter::formatDecimal)
            ?: getString(R.string.plan_usage_value_unavailable)
    }

    private fun formatEfficiencyModelFilterName(modelId: String): String {
        return when (modelId) {
            "gpt-5.6-sol" -> "Sol"
            "gpt-5.6-terra" -> "Terra"
            "gpt-5.6-luna" -> "Luna"
            "gpt-5.5" -> "5.5"
            "deepseek-v4-flash" -> "DeepSeek"
            else -> modelId
        }
    }

    override fun onDestroyView() {
        binding.rvRecommendations.adapter = null
        binding.rvRadarModels.adapter = null
        binding.rvEfficiency.adapter = null
        // 动态筛选按钮随 View 一起销毁，避免下次创建页面时复用过期签名而跳过重建。
        efficiencyFilterSignature = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        private const val CODEX_RADAR_URL = "https://codexradar.com/"
        private const val EFFICIENCY_VIEW_MODE_RANKING = "ranking"
        private const val EFFICIENCY_VIEW_MODE_GROUPED = "grouped"
        private val EFFORT_ORDER = listOf("ultra", "max", "xhigh", "high", "medium", "low")
        private val EFFICIENCY_MODEL_ORDER = listOf(
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5.5",
            "deepseek-v4-flash"
        )
        /** IQ 相同时使用固定模型/档位顺序，避免刷新后同分项无意义地跳动。 */
        private val EFFICIENCY_RANKING_COMPARATOR = compareByDescending<ModelRadarEfficiency> {
            it.iq
        }.thenBy {
            EFFICIENCY_MODEL_ORDER.indexOf(it.modelId).takeIf { index -> index >= 0 }
                ?: Int.MAX_VALUE
        }.thenBy {
            EFFORT_ORDER.indexOf(it.effort).takeIf { index -> index >= 0 }
                ?: Int.MAX_VALUE
        }
    }
}
