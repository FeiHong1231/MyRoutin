package com.hss.myroutin.activity

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hss.myroutin.R
import com.hss.myroutin.adapter.PlanUsageKeyAdapter
import com.hss.myroutin.appearance.AppAppearancePreference
import com.hss.myroutin.appearance.AppearanceMode
import com.hss.myroutin.databinding.ActivityPlanUsageInputBinding
import com.hss.myroutin.databinding.DialogRenamePlanKeyBinding
import com.hss.myroutin.model.SavedPlanKey
import com.hss.myroutin.update.AppUpdateCardState
import com.hss.myroutin.update.AppUpdateManifest
import com.hss.myroutin.update.AppUpdateUiEvent
import com.hss.myroutin.update.AppUpdateViewModel
import com.hss.myroutin.update.UpdateInstallFailureReason
import com.hss.myroutin.update.UpdateInstallResult
import com.hss.myroutin.update.UpdateInstaller
import com.hss.myroutin.viewmodel.PlanUsageUiEvent
import com.hss.myroutin.viewmodel.PlanUsageViewModel
import com.hss.myroutin.widget.MyToastD
import kotlinx.coroutines.launch
import java.io.File

/**
 * 说明：订阅 Key 用量查询页，负责生命周期和页面级交互；状态展示由独立 Renderer 处理。
 *
 * @作者 huangssh
 * @版本 2.3
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

    /** 前台更新状态独立于 Key 查询状态，旋转页面后仍可继续渲染同一次下载。 */
    private val appUpdateViewModel by lazy {
        ViewModelProvider(this).get(AppUpdateViewModel::class.java)
    }

    /** Adapter 自行绑定卡片内容，Activity 只接收展开与管理等页面级回调。 */
    private lateinit var planUsageKeyAdapter: PlanUsageKeyAdapter

    /** 页面 Renderer 统一消费 Key 查询状态，避免 Activity 直接维护控件展示分支。 */
    private lateinit var pageRenderer: PlanUsagePageRenderer

    /** 更新卡片 Renderer 统一维护检查和下载状态的视觉映射。 */
    private lateinit var appUpdateCardRenderer: AppUpdateCardRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.plan_usage_page_title)
        binding = ActivityPlanUsageInputBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initializePage()
        observeViewModel()
    }

    /**
     * 将外观设置放入系统 ActionBar 的溢出菜单，避免占用查询页的主要操作区域。
     * @param menu 当前页面的 ActionBar 菜单
     * @return 是否已成功创建菜单
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_plan_usage_input, menu)
        return true
    }

    /**
     * 处理页面级 ActionBar 菜单项；卡片内管理操作仍由独立 PopupMenu 负责。
     * @param item 用户点击的菜单项
     * @return 当前页面是否已消费该点击事件
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_check_update -> {
                appUpdateViewModel.checkForUpdate(isManual = true)
                true
            }

            R.id.action_appearance -> {
                showAppearanceDialog()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    /** 初始化固定页面控件和点击入口，避免在运行时拼装根布局与卡片容器。 */
    private fun initializePage() {
        planUsageKeyAdapter = PlanUsageKeyAdapter(
            onTogglePlanKey = viewModel::togglePlanKeyExpansion,
            onManagePlanKey = ::showPlanKeyMenu,
            onCopyPlanKey = ::copyPlanKey
        )
        pageRenderer = PlanUsagePageRenderer(binding, planUsageKeyAdapter)
        appUpdateCardRenderer = AppUpdateCardRenderer(binding)
        binding.rvPlanKeys.adapter = planUsageKeyAdapter
        binding.btnAddKey.setOnClickListener { toggleAddKeyPanel() }
        binding.btnRefreshAll.setOnClickListener { refreshAllPlanKeys() }
        binding.btnPasteKey.setOnClickListener { pasteApiKeyFromClipboard() }
        binding.btnQueryAndAdd.setOnClickListener { queryAndAddPlanKey() }
        binding.btnUpdateAction.setOnClickListener { handleUpdateAction() }
        binding.btnToggleUpdateDownload.setOnClickListener { appUpdateViewModel.toggleDownloadPause() }
        binding.btnDismissUpdate.setOnClickListener { appUpdateViewModel.dismissUpdateCard() }
    }

    /** 展示三种外观模式，单击后立即保存并应用，不增加额外确认步骤。 */
    private fun showAppearanceDialog() {
        val appearanceModes = AppearanceMode.values()
        val selectedIndex = appearanceModes.indexOf(AppAppearancePreference.getSelectedMode(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_appearance)
            .setSingleChoiceItems(
                appearanceModes.map { getString(it.displayNameResId) }.toTypedArray(),
                selectedIndex
            ) { dialog, which ->
                dialog.dismiss()
                AppAppearancePreference.saveAndApply(this, appearanceModes[which])
            }
            .show()
    }

    /** 持续渲染 ViewModel 状态，并单独消费键盘、滚动和 Toast 等一次性事件。 */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect(pageRenderer::render)
                }
                launch {
                    viewModel.events.collect(::handleUiEvent)
                }
                launch {
                    appUpdateViewModel.uiState.collect(appUpdateCardRenderer::render)
                }
                launch {
                    appUpdateViewModel.events.collect(::handleUpdateUiEvent)
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
                binding.etKeyName.text?.clear()
                binding.etApiKey.text?.clear()
            }
        }
    }

    /**
     * 消费更新相关的一次性页面事件，下载后的安装确认必须由当前前台 Activity 发起。
     * @param event 更新 ViewModel 发出的单次 UI 事件
     */
    private fun handleUpdateUiEvent(event: AppUpdateUiEvent) {
        when (event) {
            is AppUpdateUiEvent.ShowToast -> MyToastD.show(event.message)
            is AppUpdateUiEvent.ShowInstallPrompt -> showInstallPrompt(event.update, event.apkFile)
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
            MyToastD.show(getString(R.string.plan_usage_clipboard_empty))
            return
        }
        binding.etApiKey.setText(clipText)
        binding.etApiKey.setSelection(clipText.length)
        MyToastD.show(getString(R.string.plan_usage_pasted))
    }

    /**
     * 将卡片中脱敏展示的完整 Key 写入剪贴板，避免用户手动输入敏感凭据。
     * @param planKey 用户点击复制的本地订阅 Key
     */
    private fun copyPlanKey(planKey: SavedPlanKey) {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboardManager == null) {
            MyToastD.show(getString(R.string.plan_usage_copy_failed))
            return
        }
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.plan_usage_clipboard_key_label), planKey.apiKey)
        )
        MyToastD.show(getString(R.string.plan_usage_key_copied))
    }

    /** 根据当前更新卡片状态开始下载、重试下载，或打开系统安装页。 */
    private fun handleUpdateAction() {
        when (val cardState = appUpdateViewModel.uiState.value.cardState) {
            is AppUpdateCardState.Available,
            is AppUpdateCardState.DownloadFailed -> appUpdateViewModel.downloadUpdate()

            is AppUpdateCardState.Downloaded -> requestInstall(cardState.apkFile)
            else -> Unit
        }
    }

    /**
     * APK 下载并校验完成后给予一次明确确认，避免直接跳转系统安装器打断用户当前操作。
     * @param update 已下载并校验通过的更新信息
     * @param apkFile 可安全共享给系统安装器的缓存 APK
     */
    private fun showInstallPrompt(update: AppUpdateManifest, apkFile: File) {
        if (isFinishing || isDestroyed) {
            return
        }
        AlertDialog.Builder(this, R.style.Theme_MyRoutin_UpdateAlertDialog)
            .setTitle(
                getString(
                    R.string.update_downloaded_title,
                    appUpdateCardRenderer.formatVersion(update.versionName)
                )
            )
            .setMessage(R.string.update_install_prompt_message)
            .setNegativeButton(R.string.update_install_later, null)
            .setPositiveButton(R.string.action_install_now) { _, _ -> requestInstall(apkFile) }
            .show()
    }

    /**
     * 仅向 Android 系统安装器交付已校验文件；首次安装前先征得用户对未知来源安装的授权。
     * @param apkFile 已完成 SHA-256 校验的更新安装包
     */
    private fun requestInstall(apkFile: File) {
        when (val result = UpdateInstaller.requestInstall(this, apkFile)) {
            UpdateInstallResult.Started -> Unit
            UpdateInstallResult.PermissionRequired -> showInstallPermissionDialog()
            is UpdateInstallResult.Failure -> MyToastD.show(resolveInstallFailureMessage(result.reason))
        }
    }

    /**
     * 将系统安装失败分类映射为页面提示，Installer 不直接持有用户界面文案。
     * @param reason 系统安装请求的失败原因
     * @return 可直接展示的本地化文案
     */
    private fun resolveInstallFailureMessage(reason: UpdateInstallFailureReason): String {
        return getString(
            when (reason) {
                UpdateInstallFailureReason.MISSING_APK -> R.string.update_install_missing_apk
                UpdateInstallFailureReason.INSTALLER_UNAVAILABLE -> R.string.update_install_open_failed
                UpdateInstallFailureReason.INVALID_APK_PATH -> R.string.update_install_invalid_path
            }
        )
    }

    /** 用户只有在点击安装时才看到授权说明，避免在普通更新检查过程中打扰用户。 */
    private fun showInstallPermissionDialog() {
        AlertDialog.Builder(this, R.style.Theme_MyRoutin_UpdateAlertDialog)
            .setTitle(R.string.update_install_permission_title)
            .setMessage(R.string.update_install_permission_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.update_go_to_permission) { _, _ ->
                if (!UpdateInstaller.openInstallPermissionSettings(this)) {
                    MyToastD.show(getString(R.string.update_open_permission_failed))
                }
            }
            .show()
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
            MyToastD.show(getString(R.string.plan_usage_refresh_all_running))
            return
        }
        val currentPosition = state.planKeys.indexOfFirst { it.id == planKey.id }
        PopupMenu(this, anchor).apply {
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

    /** 自定义名称仅用于本地识别，不会影响接口请求中的原始 Key。 */
    private fun showRenameDialog(planKey: SavedPlanKey) {
        val dialogBinding = DialogRenamePlanKeyBinding.inflate(layoutInflater)
        dialogBinding.etPlanKeyName.setText(planKey.name)
        val dialog = AlertDialog.Builder(this)
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

    /** 删除会移除本地 Key 与对应缓存，使用二次确认避免误操作后丢失查询配置。 */
    private fun showDeleteDialog(planKey: SavedPlanKey) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.plan_usage_delete_title, planKey.name))
            .setMessage(R.string.plan_usage_delete_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deletePlanKey(planKey.id)
            }
            .show()
    }

    /**
     * 下载仅允许在首页前台进行；旋转屏幕由同一 ViewModel 接管，不能误取消正在下载的任务。
     */
    override fun onStop() {
        if (!isChangingConfigurations) {
            appUpdateViewModel.stopForegroundDownload()
        }
        super.onStop()
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
