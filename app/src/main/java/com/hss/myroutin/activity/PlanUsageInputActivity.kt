package com.hss.myroutin.activity

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.hss.myroutin.adapter.PlanUsageKeyAdapter
import com.hss.myroutin.databinding.ActivityPlanUsageInputBinding
import com.hss.myroutin.databinding.DialogRenamePlanKeyBinding
import com.hss.myroutin.model.SavedPlanKey
import com.hss.myroutin.viewmodel.PlanUsageUiEvent
import com.hss.myroutin.viewmodel.PlanUsageUiState
import com.hss.myroutin.viewmodel.PlanUsageViewModel
import com.hss.myroutin.widget.MyToastD
import kotlinx.coroutines.launch

/**
 * 说明：订阅 Key 用量查询页，负责页面状态渲染和页面级交互；卡片格式化与绑定由 Adapter 处理。
 *
 * @作者 huangssh
 * @版本 2.1
 */
class PlanUsageInputActivity : AppCompatActivity() {

    /** 页面固定结构由 XML 描述，Activity 只通过 Binding 更新页面级状态和分发交互。 */
    private lateinit var binding: ActivityPlanUsageInputBinding

    /**
     * 页面只从 ViewModel 获取状态，避免 Activity 同时承担请求、缓存和列表状态职责。
     */
    private val viewModel by lazy {
        ViewModelProvider(this).get(PlanUsageViewModel::class.java)
    }

    /** Adapter 自行绑定卡片内容，Activity 只接收展开与管理等页面级回调。 */
    private lateinit var planUsageKeyAdapter: PlanUsageKeyAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "订阅 Key 查询"
        binding = ActivityPlanUsageInputBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initializePage()
        observeViewModel()
    }

    /** 初始化固定页面控件和点击入口，避免在运行时拼装根布局与卡片容器。 */
    private fun initializePage() {
        planUsageKeyAdapter = PlanUsageKeyAdapter(
            onTogglePlanKey = viewModel::togglePlanKeyExpansion,
            onManagePlanKey = ::showPlanKeyMenu
        )
        binding.rvPlanKeys.apply {
            layoutManager = LinearLayoutManager(this@PlanUsageInputActivity)
            adapter = planUsageKeyAdapter
            isNestedScrollingEnabled = true
        }
        binding.btnAddKey.setOnClickListener { toggleAddKeyPanel() }
        binding.btnRefreshAll.setOnClickListener { refreshAllPlanKeys() }
        binding.btnPasteKey.setOnClickListener { pasteApiKeyFromClipboard() }
        binding.btnQueryAndAdd.setOnClickListener { queryAndAddPlanKey() }
    }

    /** 持续渲染 ViewModel 状态，并单独消费键盘、滚动和 Toast 等一次性事件。 */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect(::renderPage)
                }
                launch {
                    viewModel.events.collect(::handleUiEvent)
                }
            }
        }
    }

    /**
     * Activity 仅处理必须依赖 View 的短暂副作用，业务状态已由 ViewModel 完成更新。
     * @param event ViewModel 发出的单次 UI 事件
     */
    private fun handleUiEvent(event: PlanUsageUiEvent) {
        when (event) {
            is PlanUsageUiEvent.ShowToast -> MyToastD.show(event.message)
            is PlanUsageUiEvent.ScrollToPlanKey -> scrollToPlanKey(event.keyId)
            PlanUsageUiEvent.HideKeyboard -> hideKeyboard()
            PlanUsageUiEvent.ClearAddKeyInputs -> {
                binding.etKeyName.setText("")
                binding.etApiKey.setText("")
            }
        }
    }

    /** 将剪贴板第一段文本填入输入框，方便用户直接复制 plan key 后查询。 */
    private fun pasteApiKeyFromClipboard() {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboardManager
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (clipText.isBlank()) {
            MyToastD.show("剪贴板为空")
            return
        }
        binding.etApiKey.setText(clipText)
        binding.etApiKey.setSelection(clipText.length)
        MyToastD.show("已粘贴")
    }

    /**
     * 依据 ViewModel 输出的唯一状态刷新页面级控件，卡片详情由 Adapter 接收同一状态后独立绑定。
     * @param state 当前页面的完整渲染状态
     */
    private fun renderPage(state: PlanUsageUiState) {
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
        binding.btnAddKey.isEnabled = !state.isRefreshingAll
        binding.btnAddKey.text = if (state.isAddKeyPanelVisible) "收起" else "添加 Key"
        binding.btnRefreshAll.isEnabled = state.planKeys.isNotEmpty() && !state.isRefreshingAll
        binding.btnRefreshAll.text = if (state.isRefreshingAll) "刷新中..." else "刷新全部"
        binding.btnQueryAndAdd.isEnabled = !state.isAddingKey && !state.isRefreshingAll
        binding.btnQueryAndAdd.text = if (state.isAddingKey) "查询中..." else "查询并添加"
        binding.btnPasteKey.isEnabled = !state.isAddingKey && !state.isRefreshingAll
        binding.llAddKeyPanel.visibility = if (state.isAddKeyPanelVisible) View.VISIBLE else View.GONE
        binding.tvLocalDataWarning.text = state.localDataWarningMessage.orEmpty()
        binding.tvLocalDataWarning.visibility = if (state.localDataWarningMessage.isNullOrBlank()) {
            View.GONE
        } else {
            View.VISIBLE
        }
        binding.tvEmptyHint.visibility = if (state.planKeys.isEmpty()) View.VISIBLE else View.GONE
        binding.rvPlanKeys.visibility = if (state.planKeys.isEmpty()) View.GONE else View.VISIBLE
        planUsageKeyAdapter.submit(state.planKeys, state.refreshingKeyIds, state.latestErrorByKeyId)
    }

    /**
     * 添加入口的可见性由 ViewModel 更新，Activity 只保留输入框聚焦和收起键盘的 View 操作。
     */
    private fun toggleAddKeyPanel() {
        val wasVisible = viewModel.uiState.value.isAddKeyPanelVisible
        viewModel.toggleAddKeyPanel()
        val isVisible = viewModel.uiState.value.isAddKeyPanelVisible
        if (wasVisible == isVisible) {
            return
        }
        if (isVisible) {
            binding.etApiKey.requestFocus()
        } else {
            hideKeyboard()
        }
    }

    /** 将当前输入框内容交给 ViewModel 校验、查询并保存。 */
    private fun queryAndAddPlanKey() {
        viewModel.queryAndAddPlanKey(
            rawName = binding.etKeyName.text?.toString().orEmpty(),
            rawApiKey = binding.etApiKey.text?.toString().orEmpty()
        )
    }

    /** 批量刷新策略由 ViewModel 管理，Activity 仅分发按钮点击。 */
    private fun refreshAllPlanKeys() {
        viewModel.refreshAllPlanKeys()
    }

    /** 将指定 Key 定位到 ViewModel 已排序的当前列表位置。 */
    private fun scrollToPlanKey(keyId: String) {
        val index = viewModel.uiState.value.planKeys.indexOfFirst { it.id == keyId }
        if (index >= 0) {
            binding.rvPlanKeys.post { binding.rvPlanKeys.smoothScrollToPosition(index) }
        }
    }

    /**
     * 管理菜单提供单步排序、命名和删除，刻意不加入单卡刷新以保持用户确认的整体刷新规则。
     * @param anchor Adapter 传回的“管理”按钮，用于定位菜单
     * @param planKey 当前要管理的 Key
     */
    private fun showPlanKeyMenu(anchor: View, planKey: SavedPlanKey) {
        val state = viewModel.uiState.value
        if (state.isRefreshingAll) {
            MyToastD.show("正在刷新全部 Key")
            return
        }
        val currentPosition = state.planKeys.indexOfFirst { it.id == planKey.id }
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_MOVE_UP, 0, "上移一位").isEnabled = currentPosition > 0
            menu.add(0, MENU_MOVE_DOWN, 1, "下移一位").isEnabled = currentPosition in 0 until state.planKeys.lastIndex
            menu.add(0, MENU_RENAME, 2, "重命名")
            menu.add(0, MENU_DELETE, 3, "删除")
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    MENU_MOVE_UP -> viewModel.movePlanKeyByOne(planKey.id, MOVE_OFFSET_UP)
                    MENU_MOVE_DOWN -> viewModel.movePlanKeyByOne(planKey.id, MOVE_OFFSET_DOWN)
                    MENU_RENAME -> showRenameDialog(planKey)
                    MENU_DELETE -> showDeleteDialog(planKey)
                }
                true
            }
            show()
        }
    }

    /** 自定义名称仅用于本地识别，不会影响接口请求中的原始 Key。 */
    private fun showRenameDialog(planKey: SavedPlanKey) {
        val dialogBinding = DialogRenamePlanKeyBinding.inflate(layoutInflater)
        dialogBinding.etPlanKeyName.setText(planKey.name)
        val dialog = AlertDialog.Builder(this)
            .setTitle("重命名 Key")
            .setView(dialogBinding.root)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = dialogBinding.etPlanKeyName.text?.toString()?.trim().orEmpty()
                if (newName.isBlank()) {
                    MyToastD.show("名称不能为空")
                    return@setOnClickListener
                }
                viewModel.renamePlanKey(planKey.id, newName)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** 删除会移除本地 Key 与对应缓存，使用二次确认避免误操作后丢失查询配置。 */
    private fun showDeleteDialog(planKey: SavedPlanKey) {
        AlertDialog.Builder(this)
            .setTitle("删除 ${planKey.name}")
            .setMessage("删除后将移除此 Key 的本地缓存，是否继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                viewModel.deletePlanKey(planKey.id)
            }
            .show()
    }

    /** 收起输入法，避免新增或整体刷新后输入框已经隐藏但键盘仍停留在页面上。 */
    private fun hideKeyboard() {
        binding.etApiKey.clearFocus()
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputMethodManager?.hideSoftInputFromWindow(binding.etApiKey.windowToken, 0)
    }

    private companion object {
        private const val MENU_MOVE_UP = 1
        private const val MENU_MOVE_DOWN = 2
        private const val MENU_RENAME = 3
        private const val MENU_DELETE = 4
        private const val MOVE_OFFSET_UP = -1
        private const val MOVE_OFFSET_DOWN = 1
    }
}
