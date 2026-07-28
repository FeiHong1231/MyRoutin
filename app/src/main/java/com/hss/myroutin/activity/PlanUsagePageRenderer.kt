package com.hss.myroutin.activity

import android.view.View
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
        val isLocalDataReady = !state.isLoadingLocalData
        binding.tvKeyCount.text = "我的 Key（${state.planKeys.size}）"
        binding.tvRefreshStatus.text = when {
            state.isRefreshingAll -> "刷新中 ${state.refreshCurrentIndex}/${state.refreshTotalCount}"
            !state.refreshStatusText.isNullOrBlank() -> state.refreshStatusText
            else -> ""
        }
        binding.tvRefreshStatus.visibility = if (binding.tvRefreshStatus.text.isNullOrBlank()) {
            View.GONE
        } else {
            View.VISIBLE
        }
        binding.btnAddKey.isEnabled = isLocalDataReady && !state.isRefreshingAll
        binding.btnAddKey.text = if (state.isAddKeyPanelVisible) "收起" else "添加 Key"
        binding.btnRefreshAll.isEnabled =
            isLocalDataReady &&
            state.planKeys.isNotEmpty() &&
            !state.isAddingKey &&
            !state.isRefreshingAll
        binding.btnRefreshAll.text = if (state.isRefreshingAll) "刷新中..." else "刷新全部"
        binding.btnQueryAndAdd.isEnabled = isLocalDataReady && !state.isAddingKey && !state.isRefreshingAll
        binding.btnQueryAndAdd.text = if (state.isAddingKey) "查询中..." else "查询并添加"
        binding.btnPasteKey.isEnabled = isLocalDataReady && !state.isAddingKey && !state.isRefreshingAll
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
