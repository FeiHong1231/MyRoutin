package com.hss.myroutin.activity

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hss.myroutin.R
import com.hss.myroutin.databinding.ActivityMainNavigationBinding
import com.hss.myroutin.databinding.DialogModelPriceBinding
import com.hss.myroutin.fragment.ModelRadarFragment
import com.hss.myroutin.fragment.PlanUsageFragment
import com.hss.myroutin.fragment.SettingsFragment
import com.hss.myroutin.update.AppUpdateCardState
import com.hss.myroutin.update.AppUpdateManifest
import com.hss.myroutin.update.AppUpdateUiEvent
import com.hss.myroutin.update.AppUpdateViewModel
import com.hss.myroutin.update.UpdateInstallFailureReason
import com.hss.myroutin.update.UpdateInstallResult
import com.hss.myroutin.update.UpdateInstaller
import com.hss.myroutin.widget.MyToastD
import kotlinx.coroutines.launch
import java.io.File

/**
 * 说明：应用主导航容器，统一承载用量、模型和设置三个一级页面。
 *
 * @作者 huangssh
 * @版本 3.0
 */
class PlanUsageInputActivity : AppCompatActivity() {

    /** 底部导航与页面容器使用同一份 Binding，避免各业务页重复维护导航状态。 */
    private lateinit var binding: ActivityMainNavigationBinding

    /** 更新检查和下载跨用量、设置页共享，安装类事件只由当前 Activity 消费。 */
    private val appUpdateViewModel by lazy {
        ViewModelProvider(this).get(AppUpdateViewModel::class.java)
    }

    /** 记录当前一级导航，用于恢复旋转后页面和处理返回键。 */
    private var selectedNavigationItemId = R.id.navigation_usage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initializeNavigation(savedInstanceState)
        observeUpdateEvents()
    }

    /**
     * 为默认用量首页提供快捷签到入口；其他一级页不展示，避免与页面业务操作混淆。
     * @param menu 顶部 ActionBar 的操作菜单
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main_navigation, menu)
        return true
    }

    /**
     * 仅在首页显示签到图标，切换到模型数据或设置页时收起该入口。
     * @param menu 顶部 ActionBar 的操作菜单
     */
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_daily_check_in)?.isVisible =
            selectedNavigationItemId == R.id.navigation_usage
        menu.findItem(R.id.action_model_price)?.isVisible =
            selectedNavigationItemId == R.id.navigation_models
        return super.onPrepareOptionsMenu(menu)
    }

    /**
     * 处理首页工具栏操作；签到页独立承载 WebView，避免将网页生命周期耦合到首页 Fragment。
     * @param item 用户点击的工具栏菜单项
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_daily_check_in -> {
                startActivity(RoutinWebActivity.createDailyCheckInIntent(this))
                true
            }

            R.id.action_model_price -> {
                showModelPriceDialog()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 展示模型数据页使用的 Token 价格排行，价格倍数统一以最低价 luna 为基准。
     * 弹窗由主容器承载，保证从右上角菜单打开时不依赖模型页的临时 View 状态。
     */
    private fun showModelPriceDialog() {
        val dialogBinding = DialogModelPriceBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()
        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()

        // 表格需要稳定的横向空间，手机按屏宽适配，平板限制最大宽度避免内容过度拉伸。
        val maxDialogWidth = (resources.displayMetrics.density * 560).toInt()
        val preferredDialogWidth = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog.window?.setLayout(
            minOf(maxDialogWidth, preferredDialogWidth),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * 建立三个固定一级入口；Fragment 使用 add/hide 切换，保留输入和列表位置。
     * @param savedInstanceState 用于恢复配置变更前的导航位置
     */
    private fun initializeNavigation(savedInstanceState: Bundle?) {
        selectedNavigationItemId = savedInstanceState?.getInt(
            STATE_SELECTED_NAVIGATION_ITEM,
            R.id.navigation_usage
        ) ?: R.id.navigation_usage
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            showDestination(item.itemId)
            true
        }
        if (binding.bottomNavigation.selectedItemId == selectedNavigationItemId) {
            showDestination(selectedNavigationItemId)
        } else {
            binding.bottomNavigation.selectedItemId = selectedNavigationItemId
        }
    }

    /**
     * 只激活目标 Fragment，隐藏页降到 CREATED 停止无意义的 UI 收集。
     * @param itemId 底部导航菜单 ID
     */
    private fun showDestination(itemId: Int) {
        val destination = resolveDestination(itemId) ?: return
        selectedNavigationItemId = itemId
        supportActionBar?.title = getString(destination.titleResId)
        invalidateOptionsMenu()

        val fragmentManager = supportFragmentManager
        val selectedFragment = fragmentManager.findFragmentByTag(destination.tag)
            ?: destination.createFragment()
        val transaction = fragmentManager.beginTransaction().setReorderingAllowed(true)
        DESTINATIONS.forEach { candidate ->
            fragmentManager.findFragmentByTag(candidate.tag)?.let { fragment ->
                if (fragment != selectedFragment) {
                    transaction.hide(fragment)
                    transaction.setMaxLifecycle(fragment, Lifecycle.State.CREATED)
                }
            }
        }
        if (selectedFragment.isAdded) {
            transaction.show(selectedFragment)
        } else {
            transaction.add(R.id.mainContentContainer, selectedFragment, destination.tag)
        }
        transaction.setMaxLifecycle(selectedFragment, Lifecycle.State.RESUMED)
        // 同步完成固定页面切换，避免快速连点时同一标签被重复创建。
        transaction.commitNow()
    }

    /**
     * 将菜单 ID 收敛为固定页面定义，未知 ID 不应改变当前页面。
     * @param itemId 底部导航菜单 ID
     * @return 与菜单项对应的一级页面
     */
    private fun resolveDestination(itemId: Int): MainDestination? {
        return DESTINATIONS.firstOrNull { it.menuItemId == itemId }
    }

    /** 更新事件由容器统一处理，切换页面时不会丢失 Toast 或安装确认。 */
    private fun observeUpdateEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appUpdateViewModel.events.collect(::handleUpdateUiEvent)
            }
        }
    }

    /**
     * 提供给用量页和设置页的更新卡片主操作入口。
     */
    fun handleUpdateAction() {
        when (val cardState = appUpdateViewModel.uiState.value.cardState) {
            is AppUpdateCardState.Available,
            is AppUpdateCardState.Paused,
            is AppUpdateCardState.DownloadFailed -> appUpdateViewModel.downloadUpdate()

            is AppUpdateCardState.Downloaded -> requestInstall(cardState.apkFile)
            else -> Unit
        }
    }

    /**
     * 消费更新流的一次性反馈，安装跳转必须由当前前台 Activity 发起。
     * @param event 更新 ViewModel 发出的一次性事件
     */
    private fun handleUpdateUiEvent(event: AppUpdateUiEvent) {
        when (event) {
            is AppUpdateUiEvent.ShowToast -> MyToastD.show(event.message)
            is AppUpdateUiEvent.ShowInstallPrompt -> showInstallPrompt(event.update, event.apkFile)
        }
    }

    /**
     * APK 下载并校验完成后给予一次明确确认，避免直接跳转系统安装器。
     * @param update 已下载并校验通过的更新信息
     * @param apkFile 可安全共享给系统安装器的缓存 APK
     */
    private fun showInstallPrompt(update: AppUpdateManifest, apkFile: File) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this, R.style.Theme_MyRoutin_UpdateAlertDialog)
            .setTitle(
                getString(
                    R.string.update_downloaded_title,
                    formatUpdateVersion(update.versionName)
                )
            )
            .setMessage(R.string.update_install_prompt_message)
            .setNegativeButton(R.string.update_install_later, null)
            .setPositiveButton(R.string.action_install_now) { _, _ -> requestInstall(apkFile) }
            .show()
    }

    /**
     * 仅向 Android 系统安装器交付已校验文件，首次安装前引导用户授权。
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
     * 将系统安装失败分类映射为页面提示。
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

    /** 用户只在点击安装时看到授权说明，避免普通检查更新被打断。 */
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

    /** 更新卡片和确认弹窗共用同一个版本号展示口径。 */
    private fun formatUpdateVersion(versionName: String): String {
        return if (versionName.startsWith("v", ignoreCase = true)) {
            versionName
        } else {
            getString(R.string.update_version_prefixed, versionName)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_NAVIGATION_ITEM, selectedNavigationItemId)
        super.onSaveInstanceState(outState)
    }

    /** 非用量页点击返回先回到默认入口，再次返回才退出应用。 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectedNavigationItemId != R.id.navigation_usage) {
            binding.bottomNavigation.selectedItemId = R.id.navigation_usage
        } else {
            super.onBackPressed()
        }
    }

    /** 应用进入后台后自动暂停下载并保留分片，息屏返回时可继续原有进度。 */
    override fun onStop() {
        if (!isChangingConfigurations) {
            appUpdateViewModel.stopForegroundDownload()
        }
        super.onStop()
    }

    /** 固定一级页面的菜单、标题、Fragment 标记和创建方式。 */
    private data class MainDestination(
        val menuItemId: Int,
        val titleResId: Int,
        val tag: String,
        val createFragment: () -> Fragment
    )

    private companion object {
        private const val STATE_SELECTED_NAVIGATION_ITEM = "selected_navigation_item"
        private const val TAG_USAGE = "main_usage"
        private const val TAG_MODELS = "main_models"
        private const val TAG_SETTINGS = "main_settings"

        private val DESTINATIONS = listOf(
            MainDestination(
                R.id.navigation_usage,
                R.string.navigation_usage,
                TAG_USAGE,
                ::PlanUsageFragment
            ),
            MainDestination(
                R.id.navigation_models,
                R.string.navigation_models,
                TAG_MODELS,
                ::ModelRadarFragment
            ),
            MainDestination(
                R.id.navigation_settings,
                R.string.navigation_settings,
                TAG_SETTINGS,
                ::SettingsFragment
            )
        )
    }
}
