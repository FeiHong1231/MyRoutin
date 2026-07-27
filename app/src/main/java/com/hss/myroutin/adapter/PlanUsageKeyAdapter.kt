package com.hss.myroutin.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hss.myroutin.databinding.ItemPlanUsageKeyBinding
import com.hss.myroutin.model.SavedPlanKey

/**
 * 说明：订阅 Key 卡片列表适配器，仅复用卡片容器；具体业务内容由页面按 Key 状态渲染。
 *
 * @作者 huangssh
 * @版本 1.1
 */
class PlanUsageKeyAdapter(
    private val onBindCard: (ItemPlanUsageKeyBinding, SavedPlanKey, Boolean, String?) -> Unit
) : RecyclerView.Adapter<PlanUsageKeyAdapter.PlanUsageKeyViewHolder>() {

    /**
     * 适配器内部保留刷新态，避免将一次整体刷新状态写入 SP。
     */
    private var items: List<KeyCardItem> = emptyList()

    init {
        setHasStableIds(true)
    }

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
        items = keys.map { key ->
            KeyCardItem(
                key = key,
                isRefreshing = key.id in refreshingKeyIds,
                latestError = latestErrorByKeyId[key.id]
            )
        }
        notifyDataSetChanged()
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
        val item = items[position]
        onBindCard(holder.binding, item.key, item.isRefreshing, item.latestError)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].key.id.hashCode().toLong()

    /**
     * 单张卡片绑定时需要同时携带持久数据和临时请求状态。
     */
    private data class KeyCardItem(
        val key: SavedPlanKey,
        val isRefreshing: Boolean,
        val latestError: String?
    )

    /** XML 卡片通过 ViewBinding 持有，避免每次绑定时重新创建整棵 View 树。 */
    class PlanUsageKeyViewHolder(
        val binding: ItemPlanUsageKeyBinding
    ) : RecyclerView.ViewHolder(binding.root)
}
