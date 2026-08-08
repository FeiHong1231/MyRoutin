package com.hss.myroutin.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hss.myroutin.R
import com.hss.myroutin.databinding.ItemModelRadarRecommendationBinding
import com.hss.myroutin.logic.PlanUsageFormatter
import com.hss.myroutin.model.ModelRadarRecommendation

/**
 * 说明：横向展示 CodexRadar 场景首选项，让用户先按用途快速定位模型和推理档位。
 *
 * @作者 huangssh
 * @版本 3.0
 */
internal class ModelRadarRecommendationAdapter :
    ListAdapter<ModelRadarRecommendation, ModelRadarRecommendationAdapter.RecommendationViewHolder>(
        RECOMMENDATION_DIFF_CALLBACK
    ) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecommendationViewHolder {
        val binding = ItemModelRadarRecommendationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RecommendationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecommendationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** 推荐卡只消费聚合后的展示字段，不依赖第三方原始响应结构。 */
    class RecommendationViewHolder(
        private val binding: ItemModelRadarRecommendationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * 绑定一个推荐场景；缺失费用或耗时时保留占位符，不误显示为零。
         * @param recommendation 当前场景的第一推荐项
         */
        fun bind(recommendation: ModelRadarRecommendation) {
            val context = binding.root.context
            val iq = recommendation.iq?.let(PlanUsageFormatter::formatDecimal)
                ?: context.getString(R.string.plan_usage_value_unavailable)
            val duration = recommendation.averageDurationMinutes
                ?.let(PlanUsageFormatter::formatDecimal)
                ?: context.getString(R.string.plan_usage_value_unavailable)
            binding.tvRecommendationScene.text = recommendation.title
            binding.tvRecommendationModel.text = recommendation.modelName
            binding.tvRecommendationEffort.text = recommendation.effort
            binding.tvRecommendationEffort.isVisible = recommendation.effort.isNotBlank()
            binding.tvRecommendationIq.text = context.getString(R.string.model_radar_iq_value, iq)
            binding.tvRecommendationMeta.text = context.getString(
                R.string.model_radar_recommendation_meta,
                PlanUsageFormatter.formatUsd(recommendation.averageCostUsd),
                duration
            )
        }
    }

    private companion object {
        /** 场景标题与完整推荐数据共同决定卡片是否需要重绑。 */
        private val RECOMMENDATION_DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<ModelRadarRecommendation>() {
                override fun areItemsTheSame(
                    oldItem: ModelRadarRecommendation,
                    newItem: ModelRadarRecommendation
                ): Boolean {
                    return oldItem.title == newItem.title
                }

                override fun areContentsTheSame(
                    oldItem: ModelRadarRecommendation,
                    newItem: ModelRadarRecommendation
                ): Boolean {
                    return oldItem == newItem
                }
            }
    }
}
