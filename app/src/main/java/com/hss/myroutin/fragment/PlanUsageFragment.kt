package com.hss.myroutin.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hss.myroutin.R
import com.hss.myroutin.activity.AppUpdateCardRenderer
import com.hss.myroutin.activity.PlanUsageInputActivity
import com.hss.myroutin.activity.PlanUsagePageRenderer
import com.hss.myroutin.adapter.PlanUsageKeyAdapter
import com.hss.myroutin.databinding.ActivityPlanUsageInputBinding
import com.hss.myroutin.databinding.DialogRenamePlanKeyBinding
import com.hss.myroutin.model.SavedPlanKey
import com.hss.myroutin.update.AppUpdateViewModel
import com.hss.myroutin.viewmodel.PlanUsageUiEvent
import com.hss.myroutin.viewmodel.PlanUsageViewModel
import com.hss.myroutin.widget.MyToastD
import kotlinx.coroutines.launch

/**
 * 说明：用量一级页，负责订阅 Key 的添加、查询、排序与缓存状态展示。
 *
 * @作者 huangssh
 * @版本 3.0
 */
class PlanUsageFragment : Fragment() {

    /** Fragment View 销毁后及时释放 Binding，避免底部导航切换后持有旧 View。 */
    private var _binding: ActivityPlanUsageInputBinding? = null
    private val binding: ActivityPlanUsageInputBinding
        get() = requireNotNull(_binding)

    /** Key 列表状态仅属于用量页，页面在导航间切换时保留。 */
    private val viewModel by lazy {
        ViewModelProvider(this).get(PlanUsageViewModel::class.java)
    }

    /** 更新状态与设置页共享，两处更新卡片始终显示同一进度。 */
    private val appUpdateViewModel by lazy {
        ViewModelProvider(requireActivity()).get(AppUpdateViewModel::class.java)
    }

    /** Adapter 自行绑定单个 Key 卡片，Fragment 只分发页面级操作。 */
    private lateinit var planUsageKeyAdapter: PlanUsageKeyAdapter

    /** 页面状态和更新卡片分别交给独立 Renderer，避免交互代码反复设置 View。 */
    private lateinit var pageRenderer: PlanUsagePageRenderer
    private lateinit var appUpdateCardRenderer: AppUpdateCardRenderer

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityPlanUsageInputBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializePage()
        observeViewModel()
    }

    /** 初始化固定页面控件和点击入口，更新安装由主容器负责。 */
    private fun initializePage() {
        planUsageKeyAdapter = PlanUsageKeyAdapter(
            onTogglePlanKey = viewModel::togglePlanKeyExpansion,
            onManagePlanKey = ::showPlanKeyMenu,
            onCopyPlanKey = ::copyPlanKey
        )
        pageRenderer = PlanUsagePageRenderer(binding, planUsageKeyAdapter)
        appUpdateCardRenderer = AppUpdateCardRenderer(binding.updateCard)
        binding.rvPlanKeys.adapter = planUsageKeyAdapter
        binding.btnAddKey.setOnClickListener { toggleAddKeyPanel() }
        binding.btnRefreshAll.setOnClickListener { viewModel.refreshAllPlanKeys() }
        binding.btnPasteKey.setOnClickListener { pasteApiKeyFromClipboard() }
        binding.btnQueryAndAdd.setOnClickListener { queryAndAddPlanKey() }
        binding.updateCard.btnUpdateAction.setOnClickListener {
            (requireActivity() as PlanUsageInputActivity).handleUpdateAction()
        }
        binding.updateCard.btnToggleUpdateDownload.setOnClickListener {
            appUpdateViewModel.toggleDownloadPause()
        }
        binding.updateCard.btnDismissUpdate.setOnClickListener {
            appUpdateViewModel.dismissUpdateCard()
        }
    }

    /** 只在 Fragment View 可见时收集状态，导航隐藏后停止所有 View 更新。 */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(pageRenderer::render) }
                launch { viewModel.events.collect(::handleUiEvent) }
                launch { appUpdateViewModel.uiState.collect(appUpdateCardRenderer::render) }
            }
        }
    }

    /**
     * 消费 Key 页的一次性事件，业务状态仍由 ViewModel 维护。
     * @param event ViewModel 发出的页面事件
     */
    private fun handleUiEvent(event: PlanUsageUiEvent) {
        when (event) {
            is PlanUsageUiEvent.ShowToast -> MyToastD.show(event.message)
            is PlanUsageUiEvent.ScrollToPlanKey -> scrollToPlanKey(event.keyId)
            PlanUsageUiEvent.HideKeyboard -> hideKeyboard()
            PlanUsageUiEvent.ClearAddKeyInputs -> {
                binding.etKeyName.text?.clear()
                binding.etApiKey.text?.clear()
            }
        }
    }

    /** 将剪贴板第一段文本填入 Key 输入框。 */
    private fun pasteApiKeyFromClipboard() {
        val context = requireContext()
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboardManager
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (clipText.isBlank()) {
            MyToastD.show(getString(R.string.plan_usage_clipboard_empty))
            return
        }
        binding.etApiKey.setText(clipText)
        binding.etApiKey.setSelection(clipText.length)
        MyToastD.show(getString(R.string.plan_usage_pasted))
    }

    /**
     * 将完整 Key 写入剪贴板，减少用户重复输入敏感凭据。
     * @param planKey 用户点击复制的本地订阅 Key
     */
    private fun copyPlanKey(planKey: SavedPlanKey) {
        val context = requireContext()
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboardManager == null) {
            MyToastD.show(getString(R.string.plan_usage_copy_failed))
            return
        }
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.plan_usage_clipboard_key_label), planKey.apiKey)
        )
        MyToastD.show(getString(R.string.plan_usage_key_copied))
    }

    /** 根据 ViewModel 状态展开或收起新增区域，并同步处理键盘。 */
    private fun toggleAddKeyPanel() {
        val wasVisible = viewModel.uiState.value.isAddKeyPanelVisible
        viewModel.toggleAddKeyPanel()
        val isVisible = viewModel.uiState.value.isAddKeyPanelVisible
        if (wasVisible == isVisible) return
        if (isVisible) {
            binding.etApiKey.requestFocus()
        } else {
            hideKeyboard()
        }
    }

    /** 将当前输入内容交给 ViewModel 校验、查询并保存。 */
    private fun queryAndAddPlanKey() {
        viewModel.queryAndAddPlanKey(
            rawName = binding.etKeyName.text?.toString().orEmpty(),
            rawApiKey = binding.etApiKey.text?.toString().orEmpty()
        )
    }

    /**
     * 将指定 Key 定位到 ViewModel 已排序的当前列表位置。
     * @param keyId 要定位的 Key 唯一标识
     */
    private fun scrollToPlanKey(keyId: String) {
        val index = viewModel.uiState.value.planKeys.indexOfFirst { it.id == keyId }
        if (index >= 0) {
            binding.rvPlanKeys.post { binding.rvPlanKeys.smoothScrollToPosition(index) }
        }
    }

    /**
     * 管理菜单提供单步排序、命名和删除，整体刷新中禁止修改列表。
     * @param anchor 用于定位菜单的管理按钮
     * @param planKey 当前要管理的 Key
     */
    private fun showPlanKeyMenu(anchor: View, planKey: SavedPlanKey) {
        val state = viewModel.uiState.value
        if (state.isRefreshingAll) {
            MyToastD.show(getString(R.string.plan_usage_refresh_all_running))
            return
        }
        val currentPosition = state.planKeys.indexOfFirst { it.id == planKey.id }
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_MOVE_UP, 0, R.string.plan_usage_move_up).isEnabled = currentPosition > 0
            menu.add(0, MENU_MOVE_DOWN, 1, R.string.plan_usage_move_down).isEnabled =
                currentPosition in 0 until state.planKeys.lastIndex
            menu.add(0, MENU_RENAME, 2, R.string.plan_usage_rename)
            menu.add(0, MENU_DELETE, 3, R.string.action_delete)
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

    /**
     * 自定义名称仅用于本地识别，不会改变接口请求的原始 Key。
     * @param planKey 要重命名的本地 Key
     */
    private fun showRenameDialog(planKey: SavedPlanKey) {
        val dialogBinding = DialogRenamePlanKeyBinding.inflate(layoutInflater)
        dialogBinding.etPlanKeyName.setText(planKey.name)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.plan_usage_rename_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = dialogBinding.etPlanKeyName.text?.toString()?.trim().orEmpty()
                if (newName.isBlank()) {
                    MyToastD.show(getString(R.string.plan_usage_name_required))
                    return@setOnClickListener
                }
                viewModel.renamePlanKey(planKey.id, newName)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * 删除会同时移除本地 Key 和缓存，使用二次确认避免误操作。
     * @param planKey 要删除的本地 Key
     */
    private fun showDeleteDialog(planKey: SavedPlanKey) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.plan_usage_delete_title, planKey.name))
            .setMessage(R.string.plan_usage_delete_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.deletePlanKey(planKey.id) }
            .show()
    }

    /** 收起输入法，避免新增区域隐藏后键盘仍停留在屏幕上。 */
    private fun hideKeyboard() {
        binding.etApiKey.clearFocus()
        val inputMethodManager = requireContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputMethodManager?.hideSoftInputFromWindow(binding.etApiKey.windowToken, 0)
    }

    override fun onDestroyView() {
        binding.rvPlanKeys.adapter = null
        _binding = null
        super.onDestroyView()
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
