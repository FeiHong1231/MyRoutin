package com.hss.myroutin.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hss.myroutin.R
import com.hss.myroutin.databinding.ItemModelRadarModelBinding
import com.hss.myroutin.logic.PlanUsageFormatter
import com.hss.myroutin.model.ModelRadarModel
import kotlin.math.roundToLong

/**
 * 说明：模型雷达概览列表适配器，按固定模型顺序展示价格、智力与运行效率指标。
 *
 * @作者 huangssh
 * @版本 3.0
 */
internal class ModelRadarAdapter :
    ListAdapter<ModelRadarModel, ModelRadarAdapter.ModelRadarViewHolder>(MODEL_DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelRadarViewHolder {
        val binding = ItemModelRadarModelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ModelRadarViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ModelRadarViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** 模型卡通过 ViewBinding 一次更新完整指标，回收时不会残留上一模型的可见状态。 */
    class ModelRadarViewHolder(
        private val binding: ItemModelRadarModelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * 绑定模型概览；没有雷达样本的模型仍展示本地价格并明确提示数据缺失。
         * @param model 当前模型的聚合概览
         */
        fun bind(model: ModelRadarModel) {
            val context = binding.root.context
            val hasRadarData = model.iq != null
            binding.tvRadarModelName.text = model.name
            binding.tvRadarPrice.text = PlanUsageFormatter.formatUsd(model.inputPriceUsdPerMillion)
            binding.tvRadarEffort.text = PlanUsageFormatter.formatEffortLabel(model.bestEffort)
            binding.tvRadarEffort.isVisible = hasRadarData && !model.bestEffort.isNullOrBlank()
            binding.llRadarMetrics.isVisible = hasRadarData
            binding.tvRadarSummary.text = if (hasRadarData) {
                buildSummary(model)
            } else {
                context.getString(R.string.model_radar_no_intelligence_data)
            }
            if (!hasRadarData) return

            binding.tvRadarIqValue.text = formatDecimal(model.iq)
            binding.tvRadarCostValue.text = PlanUsageFormatter.formatUsd(model.averageCostUsd)
            binding.tvRadarDurationValue.text = context.getString(
                R.string.model_radar_duration_short,
                formatDecimal(model.averageDurationMinutes)
            )
            binding.tvRadarRunsValue.text = model.totalRuns?.toString()
                ?: context.getString(R.string.plan_usage_value_unavailable)
            binding.tvRadarStepsValue.text = formatDecimal(model.averageAgentSteps)
            binding.tvRadarTokensValue.text = PlanUsageFormatter.formatTokenInMillions(
                model.averageTotalTokens?.roundToLong()
            )
            binding.tvRadarCacheValue.text = PlanUsageFormatter.formatPercent(model.cacheHitRate)
        }

        /** 社区评分和题目通过率均为可选增强，按实际存在字段组合摘要。 */
        private fun buildSummary(model: ModelRadarModel): String {
            val context = binding.root.context
            val rating = model.communityRating
            val ratingCount = model.communityRatingCount
            val passedTasks = model.passedTasks
            val validTasks = model.validTasks
            return when {
                rating != null && ratingCount != null && passedTasks != null && validTasks != null -> {
                    context.getString(
                        R.string.model_radar_summary_rating_and_tasks,
                        PlanUsageFormatter.formatDecimal(rating),
                        ratingCount,
                        passedTasks,
                        validTasks
                    )
                }

                rating != null && ratingCount != null -> context.getString(
                    R.string.model_radar_summary_rating,
                    PlanUsageFormatter.formatDecimal(rating),
                    ratingCount
                )

                passedTasks != null && validTasks != null -> context.getString(
                    R.string.model_radar_summary_tasks,
                    passedTasks,
                    validTasks
                )

                else -> context.getString(R.string.model_radar_summary_unavailable)
            }
        }

        /** 小数指标统一沿用订阅页精度，空值保留既有占位符。 */
        private fun formatDecimal(value: Double?): String {
            return value?.let(PlanUsageFormatter::formatDecimal)
                ?: binding.root.context.getString(R.string.plan_usage_value_unavailable)
        }
    }

    private companion object {
        /** 模型 ID 标识卡片，任一聚合指标变化都会触发内容更新。 */
        private val MODEL_DIFF_CALLBACK = object : DiffUtil.ItemCallback<ModelRadarModel>() {
            override fun areItemsTheSame(oldItem: ModelRadarModel, newItem: ModelRadarModel): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: ModelRadarModel, newItem: ModelRadarModel): Boolean {
                return oldItem == newItem
            }
        }
    }
}
