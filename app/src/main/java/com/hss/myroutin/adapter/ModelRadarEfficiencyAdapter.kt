package com.hss.myroutin.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hss.myroutin.R
import com.hss.myroutin.databinding.ItemModelRadarEfficiencyGroupBinding
import com.hss.myroutin.databinding.ItemModelRadarEfficiencyBinding
import com.hss.myroutin.logic.PlanUsageFormatter
import com.hss.myroutin.model.ModelRadarEfficiency
import kotlin.math.roundToLong

/**
 * 说明：智力效率档位卡片适配器，按官网模型分组顺序展示手机端需要的核心指标。
 *
 * @作者 huangssh
 * @版本 3.0
 */
internal class ModelRadarEfficiencyAdapter :
    ListAdapter<ModelRadarEfficiencyAdapter.EfficiencyRow, RecyclerView.ViewHolder>(
        EFFICIENCY_ROW_DIFF_CALLBACK
    ) {

    /** 默认展开主力三组，长尾模型收起，首屏先呈现用户最常比较的结果。 */
    private val collapsedModelIds = mutableSetOf("gpt-5.5", "deepseek-v4-flash")
    private var points: List<ModelRadarEfficiency> = emptyList()
    private var onPointClick: ((ModelRadarEfficiency) -> Unit)? = null

    /**
     * 设置卡片点击回调，详情面板由 Fragment 持有生命周期并负责展示。
     * @param listener 当前效率卡片被点击后的处理函数
     */
    fun setOnPointClickListener(listener: (ModelRadarEfficiency) -> Unit) {
        onPointClick = listener
    }

    /** 筛选到某个模型时自动展开它，保证筛选结果不会只剩一个折叠标题。 */
    fun expandModel(modelId: String) {
        collapsedModelIds -= modelId
    }

    /**
     * 按模型构建可折叠的手机端列表，筛选后仍保持官网的模型与档位顺序。
     * @param values 当前筛选条件下的效率点
     */
    fun submitPoints(values: List<ModelRadarEfficiency>) {
        points = values
        val rows = buildList {
            values.groupBy(ModelRadarEfficiency::modelId).forEach { (modelId, modelPoints) ->
                add(
                    EfficiencyRow.Group(
                        modelId = modelId,
                        modelName = modelPoints.first().modelName,
                        pointCount = modelPoints.size,
                        expanded = modelId !in collapsedModelIds
                    )
                )
                if (modelId !in collapsedModelIds) {
                    modelPoints.forEach { point -> add(EfficiencyRow.Point(point)) }
                }
            }
        }
        submitList(rows)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is EfficiencyRow.Group -> VIEW_TYPE_GROUP
            is EfficiencyRow.Point -> VIEW_TYPE_POINT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_GROUP) {
            GroupViewHolder(
                ItemModelRadarEfficiencyGroupBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        } else {
            PointViewHolder(
                ItemModelRadarEfficiencyBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is EfficiencyRow.Group -> (holder as GroupViewHolder).bind(row)
            is EfficiencyRow.Point -> (holder as PointViewHolder).bind(row.value)
        }
    }

    /** 模型组标题承担折叠入口，卡片数据仍由下方 Point 行独立绑定。 */
    private inner class GroupViewHolder(
        private val binding: ItemModelRadarEfficiencyGroupBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(group: EfficiencyRow.Group) {
            binding.tvEfficiencyGroupName.text = group.modelName
            binding.tvEfficiencyGroupMeta.text = binding.root.context.getString(
                R.string.model_radar_efficiency_group_meta,
                group.pointCount
            )
            binding.ivEfficiencyGroupArrow.rotation = if (group.expanded) 90f else 0f
            binding.viewEfficiencyGroupAccent.setBackgroundColor(
                ContextCompat.getColor(binding.root.context, resolveAccent(group.modelId))
            )
            binding.root.setOnClickListener {
                if (group.expanded) {
                    collapsedModelIds += group.modelId
                } else {
                    collapsedModelIds -= group.modelId
                }
                submitPoints(points)
            }
        }
    }

    /** 单张效率卡完整绑定所有可选指标，避免 RecyclerView 回收时残留旧数据。 */
    private inner class PointViewHolder(
        private val binding: ItemModelRadarEfficiencyBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * 绑定模型档位的核心智力、费用、耗时和运行效率数据。
         * @param point 当前模型档位的聚合指标
         */
        fun bind(point: ModelRadarEfficiency) {
            val context = binding.root.context
            binding.viewEfficiencyAccent.setBackgroundColor(
                ContextCompat.getColor(context, resolveAccent(point.modelId))
            )
            binding.tvEfficiencyModelName.text = point.modelName
            binding.tvEfficiencyEffort.text = point.effort
            binding.tvEfficiencyRuns.text = point.recentRuns24h?.let {
                context.getString(R.string.model_radar_efficiency_runs_short, it)
            } ?: context.getString(R.string.plan_usage_value_unavailable)
            binding.tvEfficiencyIq.text = PlanUsageFormatter.formatDecimal(point.iq)
            binding.tvEfficiencyCost.text = PlanUsageFormatter.formatUsd(point.averageCostUsd)
            binding.tvEfficiencyDuration.text = point.averageDurationMinutes
                ?.let(PlanUsageFormatter::formatDecimal)
                ?.let { context.getString(R.string.model_radar_duration_minutes, it) }
                ?: context.getString(R.string.plan_usage_value_unavailable)
            binding.tvEfficiencyDetails.text = context.getString(
                R.string.model_radar_efficiency_details,
                formatDecimal(point.averageAgentSteps, context),
                PlanUsageFormatter.formatTokenInMillions(point.averageTotalTokens?.roundToLong()),
                PlanUsageFormatter.formatPercent(point.cacheHitRate)
            )
            binding.root.setOnClickListener { onPointClick?.invoke(point) }
        }

        private fun formatDecimal(value: Double?, context: android.content.Context): String {
            return value?.let(PlanUsageFormatter::formatDecimal)
                ?: context.getString(R.string.plan_usage_value_unavailable)
        }
    }

    /** 用模型色区分卡片分组，具体颜色由浅色和深色资源分别控制。 */
    private fun resolveAccent(modelId: String): Int {
        return when (modelId) {
            "gpt-5.6-sol" -> R.color.model_radar_sol_accent
            "gpt-5.6-terra" -> R.color.model_radar_terra_accent
            "gpt-5.6-luna" -> R.color.model_radar_luna_accent
            "gpt-5.5" -> R.color.model_radar_55_accent
            "deepseek-v4-flash" -> R.color.model_radar_deepseek_accent
            else -> R.color.plan_usage_brand_primary
        }
    }

    private companion object {
        private const val VIEW_TYPE_GROUP = 0
        private const val VIEW_TYPE_POINT = 1

        /** 模型组和档位共同构成稳定键，任何指标变化都应更新对应行。 */
        private val EFFICIENCY_ROW_DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<EfficiencyRow>() {
                override fun areItemsTheSame(
                    oldItem: EfficiencyRow,
                    newItem: EfficiencyRow
                ): Boolean {
                    return oldItem.id == newItem.id
                }

                override fun areContentsTheSame(
                    oldItem: EfficiencyRow,
                    newItem: EfficiencyRow
                ): Boolean {
                    return oldItem == newItem
                }
            }
    }

    /** 效率页列表同时承载模型组标题与单个档位卡片。 */
    internal sealed interface EfficiencyRow {
        val id: String

        data class Group(
            val modelId: String,
            val modelName: String,
            val pointCount: Int,
            val expanded: Boolean
        ) : EfficiencyRow {
            override val id: String = "group:$modelId"
        }

        data class Point(val value: ModelRadarEfficiency) : EfficiencyRow {
            override val id: String = "point:${value.id}"
        }
    }
}
