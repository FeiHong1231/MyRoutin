package com.hss.myroutin.activity

import android.content.ClipboardManager
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
import androidx.recyclerview.widget.LinearLayoutManager
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
import com.hss.myroutin.update.AppUpdateUiState
import com.hss.myroutin.update.AppUpdateViewModel
import com.hss.myroutin.update.UpdateDownloadProgress
import com.hss.myroutin.update.UpdateInstallResult
import com.hss.myroutin.update.UpdateInstaller
import com.hss.myroutin.viewmodel.PlanUsageUiEvent
import com.hss.myroutin.viewmodel.PlanUsageUiState
import com.hss.myroutin.viewmodel.PlanUsageViewModel
import com.hss.myroutin.widget.MyToastD
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * 说明：订阅 Key 用量查询页，负责页面状态渲染和页面级交互；卡片格式化与绑定由 Adapter 处理。
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "订阅 Key 查询"
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
        binding.btnUpdateAction.setOnClickListener { handleUpdateAction() }
        binding.btnToggleUpdateDownload.setOnClickListener { appUpdateViewModel.toggleDownloadPause() }
        binding.btnDismissUpdate.setOnClickListener { appUpdateViewModel.dismissUpdateCard() }
    }

    /** 展示三种外观模式，单击后立即保存并应用，不增加额外确认步骤。 */
    private fun showAppearanceDialog() {
        val appearanceModes = AppearanceMode.values()
        val selectedIndex = appearanceModes.indexOf(AppAppearancePreference.getSelectedMode(this))
        AlertDialog.Builder(this)
            .setTitle("外观")
            .setSingleChoiceItems(
                appearanceModes.map { it.displayName }.toTypedArray(),
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
                    viewModel.uiState.collect(::renderPage)
                }
                launch {
                    viewModel.events.collect(::handleUiEvent)
                }
                launch {
                    appUpdateViewModel.uiState.collect(::renderUpdateCard)
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
                binding.etKeyName.setText("")
                binding.etApiKey.setText("")
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

    /**
     * 依据更新状态显示或隐藏首页下载卡片；环形进度只在应用停留前台时出现。
     * @param state 当前更新检查、下载或安装入口状态
     */
    private fun renderUpdateCard(state: AppUpdateUiState) {
        if (state.isChecking && state.isManualChecking && state.cardState is AppUpdateCardState.Hidden) {
            showUpdateCheckingCard()
            return
        }
        when (val cardState = state.cardState) {
            AppUpdateCardState.Hidden -> {
                binding.ucpUpdateProgress.isIndeterminate = false
                binding.llUpdateCard.visibility = View.GONE
            }

            is AppUpdateCardState.Available -> {
                showUpdateActionCard(
                    title = "发现新版本 ${formatUpdateVersion(cardState.update.versionName)}",
                    detail = "已准备好下载",
                    actionText = "下载更新"
                )
            }

            is AppUpdateCardState.Downloading -> {
                showDownloadProgress(cardState.update, cardState.progress, isPaused = false)
            }

            is AppUpdateCardState.Paused -> {
                showDownloadProgress(cardState.update, cardState.progress, isPaused = true)
            }

            is AppUpdateCardState.DownloadFailed -> {
                showUpdateActionCard(
                    title = "下载失败",
                    detail = cardState.userMessage,
                    actionText = "重试"
                )
            }

            is AppUpdateCardState.Downloaded -> {
                showUpdateActionCard(
                    title = "新版本 ${formatUpdateVersion(cardState.update.versionName)} 已下载",
                    detail = "安装包已完成校验",
                    actionText = "立即安装"
                )
            }
        }
    }

    /**
     * 将下载中和已暂停状态统一映射到圆环与中心图标，点击中心图标可在暂停和继续之间切换。
     * @param update 当前下载的版本信息
     * @param progress 已下载字节与总字节进度
     * @param isPaused 是否展示继续下载入口
     */
    private fun showDownloadProgress(
        update: AppUpdateManifest,
        progress: UpdateDownloadProgress,
        isPaused: Boolean
    ) {
        val downloadPercent = calculateDownloadPercent(progress)
        binding.llUpdateCard.visibility = View.VISIBLE
        binding.tvUpdateTitle.text = if (isPaused) {
            "新版本 ${formatUpdateVersion(update.versionName)} 已暂停"
        } else {
            "新版本 ${formatUpdateVersion(update.versionName)} 正在下载${downloadPercent?.let { " $it%" }.orEmpty()}"
        }
        binding.tvUpdateDetail.text = formatDownloadProgress(progress)
        binding.flUpdateProgress.visibility = View.VISIBLE
        binding.btnUpdateAction.visibility = View.GONE
        binding.btnToggleUpdateDownload.visibility = View.VISIBLE
        binding.btnToggleUpdateDownload.setImageResource(
            if (isPaused) R.drawable.ic_update_float_play else R.drawable.ic_update_float_pause
        )
        binding.btnToggleUpdateDownload.contentDescription = if (isPaused) "继续下载" else "暂停下载"
        binding.btnDismissUpdate.contentDescription = if (isPaused) "关闭更新提示" else "取消下载"
        binding.ucpUpdateProgress.isIndeterminate = false
        binding.ucpUpdateProgress.progress = downloadPercent ?: 0
    }

    /** 手动检查时明确展示当前请求状态，避免用户只看到“正在处理”却不知道请求是否仍在进行。 */
    private fun showUpdateCheckingCard() {
        binding.llUpdateCard.visibility = View.VISIBLE
        binding.tvUpdateTitle.text = "正在检查更新"
        binding.tvUpdateDetail.text = "正在连接 GitHub Release"
        binding.flUpdateProgress.visibility = View.VISIBLE
        binding.ucpUpdateProgress.progress = 0
        binding.ucpUpdateProgress.isIndeterminate = true
        binding.btnUpdateAction.visibility = View.GONE
        binding.btnToggleUpdateDownload.visibility = View.GONE
        binding.btnDismissUpdate.contentDescription = "取消检查更新"
    }

    /**
     * 渲染可点击操作的更新卡片，下载完成、待下载和失败重试共用同一套视觉结构。
     * @param title 卡片主标题
     * @param detail 卡片辅助说明
     * @param actionText 右侧操作按钮文案
     */
    private fun showUpdateActionCard(title: String, detail: String, actionText: String) {
        binding.llUpdateCard.visibility = View.VISIBLE
        binding.tvUpdateTitle.text = title
        binding.tvUpdateDetail.text = detail
        binding.flUpdateProgress.visibility = View.GONE
        binding.ucpUpdateProgress.isIndeterminate = false
        binding.btnUpdateAction.visibility = View.VISIBLE
        binding.btnToggleUpdateDownload.visibility = View.GONE
        binding.btnUpdateAction.text = actionText
        binding.btnDismissUpdate.contentDescription = "关闭更新提示"
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
        AlertDialog.Builder(this)
            .setTitle("新版本 ${formatUpdateVersion(update.versionName)} 已下载")
            .setMessage("安装包已完成校验，是否立即安装？")
            .setNegativeButton("稍后", null)
            .setPositiveButton("立即安装") { _, _ -> requestInstall(apkFile) }
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
            is UpdateInstallResult.Failure -> MyToastD.show(result.userMessage)
        }
    }

    /** 用户只有在点击安装时才看到授权说明，避免在普通更新检查过程中打扰用户。 */
    private fun showInstallPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要安装授权")
            .setMessage("首次安装更新，需要允许 MyRoutin 安装未知来源应用。授权后请返回此处再次点击“立即安装”。")
            .setNegativeButton("取消", null)
            .setPositiveButton("去授权") { _, _ ->
                if (!UpdateInstaller.openInstallPermissionSettings(this)) {
                    MyToastD.show("无法打开安装授权设置")
                }
            }
            .show()
    }

    /**
     * 将字节数以可读单位展示，不足 1 MB 时保留 KB，方便用户判断下载是否正常推进。
     * @param bytes 已下载或总计的字节数
     * @return 适合下载卡片展示的文件大小
     */
    private fun formatDataSize(bytes: Long): String {
        return if (bytes < BYTES_PER_MEGABYTE) {
            "${bytes / BYTES_PER_KILOBYTE} KB"
        } else {
            String.format(Locale.getDefault(), "%.1f MB", bytes / BYTES_PER_MEGABYTE.toDouble())
        }
    }

    /**
     * 组合实时下载量和远端文件大小；服务端未返回大小时仍展示已下载字节数。
     * @param progress 当前下载进度
     * @return 下载卡片的辅助文案
     */
    private fun formatDownloadProgress(progress: UpdateDownloadProgress): String {
        return progress.totalBytes?.let { totalBytes ->
            "${formatDataSize(progress.downloadedBytes)} / ${formatDataSize(totalBytes)}"
        } ?: "${formatDataSize(progress.downloadedBytes)} 已下载"
    }

    /**
     * 将字节进度转换为标题和圆环共用的整数百分比；未提供有效总大小时不虚构进度。
     * @param progress 当前下载进度
     * @return 0 到 100 的百分比，未知总大小时为 null
     */
    private fun calculateDownloadPercent(progress: UpdateDownloadProgress): Int? {
        val totalBytes = progress.totalBytes?.takeIf { it > 0L } ?: return null
        return ((progress.downloadedBytes.toDouble() / totalBytes) * UPDATE_PROGRESS_MAX)
            .toInt()
            .coerceIn(0, UPDATE_PROGRESS_MAX)
    }

    /** 统一为远端版本号补充 v 前缀，避免发布清单误填 v2.2 时展示成 vv2.2。 */
    private fun formatUpdateVersion(versionName: String): String {
        return if (versionName.startsWith("v", ignoreCase = true)) versionName else "v${versionName}"
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
        private const val UPDATE_PROGRESS_MAX = 100
        private const val BYTES_PER_KILOBYTE = 1024L
        private const val BYTES_PER_MEGABYTE = BYTES_PER_KILOBYTE * BYTES_PER_KILOBYTE
    }
}
