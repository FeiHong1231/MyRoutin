package com.hss.myroutin.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hss.myroutin.databinding.ItemPlanUsageKeyBinding
import com.hss.myroutin.model.SavedPlanKey

/**
 * 说明：订阅 Key 卡片列表适配器，只负责列表差量更新、ViewHolder 生命周期和卡片交互回调转发。
 *
 * @param onTogglePlanKey 展开或收起当前 Key 的交互回调
 * @param onManagePlanKey 打开当前 Key 管理入口的交互回调
 * @param onCopyPlanKey 将当前完整 Key 写入系统剪贴板的交互回调
 * @作者 huangssh
 * @版本 2.3
 */
class PlanUsageKeyAdapter(
    onTogglePlanKey: (String) -> Unit,
    onManagePlanKey: (View, SavedPlanKey) -> Unit,
    onCopyPlanKey: (SavedPlanKey) -> Unit
) : ListAdapter<PlanUsageKeyAdapter.KeyCardItem, PlanUsageKeyAdapter.PlanUsageKeyViewHolder>(
    KEY_CARD_ITEM_DIFF_CALLBACK
) {

    /** 卡片具体展示逻辑独立于 RecyclerView 生命周期，避免 Adapter 同时承担格式化和绘制职责。 */
    private val cardBinder = PlanUsageKeyCardBinder(
        onTogglePlanKey,
        onManagePlanKey,
        onCopyPlanKey
    )

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
        cardBinder.bind(holder.binding, item.key, item.isRefreshing, item.latestError)
    }

    /** 单张卡片的完整比较对象，确保 DiffUtil 不会漏掉会话内刷新态变化。 */
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
    }
}
