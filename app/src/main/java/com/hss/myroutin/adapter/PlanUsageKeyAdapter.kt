package com.hss.myroutin.adapter

import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.hss.myroutin.R
import com.hss.myroutin.model.SavedPlanKey
import com.hss.myroutin.widget.dp

/**
 * 说明：订阅 Key 卡片列表适配器，仅复用卡片容器；具体业务内容由页面按 Key 状态渲染。
 *
 * @作者 huangssh
 * @版本 1.1
 */
class PlanUsageKeyAdapter(
    private val onBindCard: (LinearLayout, SavedPlanKey, Boolean) -> Unit
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
     */
    fun submit(keys: List<SavedPlanKey>, refreshingKeyIds: Set<String>) {
        items = keys.map { key ->
            KeyCardItem(key, key.id in refreshingKeyIds)
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlanUsageKeyViewHolder {
        val card = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            // 卡片纵向更紧凑，左右保留更宽的操作安全边距。
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            background = GradientDrawable().apply {
                cornerRadius = 10.dp.toFloat()
                setColor(context.getColor(R.color.white))
            }
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 16.dp
                rightMargin = 16.dp
                topMargin = 4.dp
                bottomMargin = 4.dp
            }
        }
        return PlanUsageKeyViewHolder(card)
    }

    override fun onBindViewHolder(holder: PlanUsageKeyViewHolder, position: Int) {
        val item = items[position]
        onBindCard(holder.card, item.key, item.isRefreshing)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].key.id.hashCode().toLong()

    /**
     * 单张卡片绑定时需要同时携带持久数据和临时请求状态。
     */
    private data class KeyCardItem(
        val key: SavedPlanKey,
        val isRefreshing: Boolean
    )

    class PlanUsageKeyViewHolder(val card: LinearLayout) : RecyclerView.ViewHolder(card)
}
