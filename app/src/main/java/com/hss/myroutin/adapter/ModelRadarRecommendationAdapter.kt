package com.hss.myroutin.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hss.myroutin.databinding.ItemModelRadarRecommendationBinding
import com.hss.myroutin.model.ModelRadarRecommendation

/**
 * 说明：横向展示 CodexRadar 场景选择项，选中场景由页面统一渲染首选与备选对比。
 *
 * @作者 huangssh
 * @版本 3.0
 */
internal class ModelRadarRecommendationAdapter :
    ListAdapter<ModelRadarRecommendation, ModelRadarRecommendationAdapter.RecommendationViewHolder>(
        RECOMMENDATION_DIFF_CALLBACK
    ) {

    /** 当前场景只影响按钮选中态，不改变服务端提供的场景顺序。 */
    private var selectedTitle: String? = null

    /** 场景点击回调交给 Fragment 切换同一份快照中的决策内容。 */
    private var onScenarioClick: ((String) -> Unit)? = null

    /**
     * 设置场景切换回调。
     * @param listener 被点击场景的标题回调
     */
    fun setOnScenarioClickListener(listener: (String) -> Unit) {
        onScenarioClick = listener
    }

    /**
     * 更新选中场景，只刷新旧、新两个位置，避免整个横向列表闪动。
     * @param title 当前选中的场景标题
     */
    fun setSelectedTitle(title: String?) {
        if (selectedTitle == title) return
        val previousPosition = currentList.indexOfFirst { it.title == selectedTitle }
        val currentPosition = currentList.indexOfFirst { it.title == title }
        selectedTitle = title
        if (previousPosition >= 0) notifyItemChanged(previousPosition)
        if (currentPosition >= 0) notifyItemChanged(currentPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecommendationViewHolder {
        val binding = ItemModelRadarRecommendationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RecommendationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecommendationViewHolder, position: Int) {
        val recommendation = getItem(position)
        holder.bind(
            recommendation = recommendation,
            isSelected = recommendation.title == selectedTitle,
            onClick = onScenarioClick
        )
    }

    /** 场景按钮只消费标题，不把首选模型指标重复放进横向列表。 */
    class RecommendationViewHolder(
        private val binding: ItemModelRadarRecommendationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * 绑定一个场景按钮，选中态沿用模型页顶部筛选控件的语义资源。
         * @param recommendation 当前场景的代表项
         * @param isSelected 是否为当前决策场景
         * @param onClick 场景点击回调
         */
        fun bind(
            recommendation: ModelRadarRecommendation,
            isSelected: Boolean,
            onClick: ((String) -> Unit)?
        ) {
            binding.root.isSelected = isSelected
            binding.tvRecommendationScene.text = recommendation.title
            binding.root.setOnClickListener { onClick?.invoke(recommendation.title) }
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
