package com.hss.myroutin.activity

import android.content.pm.ApplicationInfo
import android.view.View
import com.hss.myroutin.R
import com.hss.myroutin.adapter.PlanUsageKeyAdapter
import com.hss.myroutin.databinding.ActivityPlanUsageInputBinding
import com.hss.myroutin.viewmodel.PlanUsageUiState

/**
 * 说明：订阅 Key 页面状态渲染器，只负责把 ViewModel 状态映射到页面控件和列表数据。
 *
 * @param binding 查询页固定控件的 ViewBinding
 * @param planUsageKeyAdapter 接收同一页面状态中的 Key 列表
 * @作者 huangssh
 * @版本 2.3
 */
internal class PlanUsagePageRenderer(
    private val binding: ActivityPlanUsageInputBinding,
    private val planUsageKeyAdapter: PlanUsageKeyAdapter
) {

    /**
     * 依据 ViewModel 输出的唯一状态刷新页面级控件，卡片详情由 Adapter 接收同一状态后独立绑定。
     * @param state 当前页面的完整渲染状态
     */
    fun render(state: PlanUsageUiState) {
        val context = binding.root.context
        val isLocalDataReady = !state.isLoadingLocalData
        val isRefreshingAnyKey = state.refreshingKeyIds.isNotEmpty()
        val canPullToRefresh = isLocalDataReady &&
            state.planKeys.isNotEmpty() &&
            !state.isAddingKey &&
            !state.isRefreshingAll &&
            !isRefreshingAnyKey
        binding.swipeRefreshPlanUsage.isEnabled = canPullToRefresh
        binding.swipeRefreshPlanUsage.isRefreshing = state.isRefreshingAll
        binding.tvKeyCount.text = context.getString(R.string.plan_usage_key_count, state.planKeys.size)
        binding.tvRefreshStatus.text = when {
            state.isRefreshingAll -> context.getString(
                R.string.plan_usage_refresh_progress,
                state.refreshCurrentIndex,
                state.refreshTotalCount
            )
            else -> null
        }
        val isShowingRefreshStatus = !binding.tvRefreshStatus.text.isNullOrBlank()
        binding.tvRefreshStatus.visibility = if (isShowingRefreshStatus) View.VISIBLE else View.GONE
        // 刷新状态彻底移除加号，但标题行的最小高度继续稳定布局，避免页面抖动。
        binding.btnAddKey.visibility = if (isShowingRefreshStatus) View.GONE else View.VISIBLE
        binding.btnAddKey.contentDescription = context.getString(
            if (state.isAddKeyPanelVisible) R.string.action_collapse else R.string.action_add_key
        )
        val targetAddIconRotation = if (state.isAddKeyPanelVisible) 45f else 0f
        if (binding.btnAddKey.rotation != targetAddIconRotation) {
            binding.btnAddKey.animate()
                .rotation(targetAddIconRotation)
                .setDuration(180L)
                .start()
        }
        binding.btnAddKey.isEnabled = isLocalDataReady && !state.isRefreshingAll && !isRefreshingAnyKey
        val isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        binding.btnFakeWeeklyReset.visibility = if (isDebuggable) View.VISIBLE else View.GONE
        binding.btnFakeWeeklyReset.isEnabled = canPullToRefresh
        binding.btnQueryAndAdd.isEnabled =
            isLocalDataReady && !state.isAddingKey && !state.isRefreshingAll && !isRefreshingAnyKey
        binding.btnQueryAndAdd.text = context.getString(
            if (state.isAddingKey) R.string.plan_usage_querying else R.string.action_query_and_add
        )
        binding.btnPasteKey.isEnabled =
            isLocalDataReady && !state.isAddingKey && !state.isRefreshingAll && !isRefreshingAnyKey
        binding.llAddKeyPanel.visibility = if (state.isAddKeyPanelVisible) View.VISIBLE else View.GONE
        binding.tvLocalDataWarning.text = state.localDataWarningMessage.orEmpty()
        binding.tvLocalDataWarning.visibility = if (state.localDataWarningMessage.isNullOrBlank()) {
            View.GONE
        } else {
            View.VISIBLE
        }
        binding.tvEmptyHint.visibility = if (isLocalDataReady && state.planKeys.isEmpty()) View.VISIBLE else View.GONE
        binding.rvPlanKeys.visibility = if (state.planKeys.isEmpty()) View.GONE else View.VISIBLE
        planUsageKeyAdapter.submit(state.planKeys, state.refreshingKeyIds, state.latestErrorByKeyId)
    }
}
