package com.hss.myroutin.fragment

import android.app.Activity
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hss.myroutin.BuildConfig
import com.hss.myroutin.R
import com.hss.myroutin.activity.AppUpdateCardRenderer
import com.hss.myroutin.activity.PlanUsageInputActivity
import com.hss.myroutin.activity.RoutinWebActivity
import com.hss.myroutin.appearance.AppAppearancePreference
import com.hss.myroutin.appearance.AppearanceMode
import com.hss.myroutin.databinding.FragmentSettingsBinding
import com.hss.myroutin.databinding.DialogWeeklyResetStatsBinding
import com.hss.myroutin.databinding.ItemWeeklyResetStatsKeyBinding
import com.hss.myroutin.logic.PlanUsageFormatter
import com.hss.myroutin.model.RoutinRecentGroup
import com.hss.myroutin.store.RoutinRecentGroupStore
import com.hss.myroutin.update.AppUpdateViewModel
import com.hss.myroutin.viewmodel.PlanUsageViewModel
import com.hss.myroutin.widget.MyToastD
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 说明：设置一级页，承接外观、版本检查和更新下载等应用级操作。
 *
 * @作者 huangssh
 * @版本 3.0
 */
class SettingsFragment : Fragment() {

    /** 设置页 View 销毁后释放 Binding，保留 Fragment 本身以恢复导航状态。 */
    private var _binding: FragmentSettingsBinding? = null
    private val binding: FragmentSettingsBinding
        get() = requireNotNull(_binding)

    /** 与用量页共享更新状态，确保下载进度和安装入口始终一致。 */
    private val appUpdateViewModel by lazy {
        ViewModelProvider(requireActivity()).get(AppUpdateViewModel::class.java)
    }

    /** 与用量页共享 Key 缓存，点击统计入口时读取所有 Key 的当前累计金额。 */
    private val planUsageViewModel by lazy {
        ViewModelProvider(requireActivity()).get(PlanUsageViewModel::class.java)
    }

    /** 更新卡片复用用量页的状态映射，设置页只提供入口和容器。 */
    private lateinit var appUpdateCardRenderer: AppUpdateCardRenderer

    /** 最近分组只保存展示字段，网页登录凭证继续由 Routin WebView Cookie 管理。 */
    private val routinRecentGroupStore by lazy { RoutinRecentGroupStore(requireContext()) }

    /** 从网页同步成功后回到设置页写入结果；取消或失败时保留已有结果。 */
    private val routinGroupSyncLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val groupName = data.getStringExtra(RoutinWebActivity.EXTRA_SYNC_GROUP_NAME)
            ?.trim()
            .orEmpty()
        val requestTime = data.getStringExtra(RoutinWebActivity.EXTRA_SYNC_REQUEST_TIME)
            ?.trim()
            .orEmpty()
        val multiplier = data.getDoubleExtra(
            RoutinWebActivity.EXTRA_SYNC_MULTIPLIER,
            Double.NaN
        )
        if (groupName.isEmpty() || requestTime.isEmpty() || !multiplier.isFinite()) {
            MyToastD.show(getString(R.string.settings_routin_account_sync_failed))
            return@registerForActivityResult
        }
        val recentGroup = RoutinRecentGroup(groupName, multiplier, requestTime)
        routinRecentGroupStore.save(recentGroup)
        if (_binding != null) renderRoutinRecentGroup()
        MyToastD.show(getString(R.string.settings_routin_account_sync_success))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializePage()
        observeUpdateState()
    }

    /** 初始化设置行和共用更新卡片，所有行保持完整可点击区域。 */
    private fun initializePage() {
        appUpdateCardRenderer = AppUpdateCardRenderer(binding.updateCard)
        renderAppearanceMode()
        renderRoutinRecentGroup()
        binding.tvCurrentVersion.text = getString(
            R.string.settings_current_version,
            BuildConfig.VERSION_NAME
        )
        binding.llRoutinAccountSetting.setOnClickListener {
            binding.tvRoutinAccountValue.text = getString(
                R.string.settings_routin_account_syncing
            )
            routinGroupSyncLauncher.launch(
                RoutinWebActivity.createRecentGroupSyncIntent(requireContext())
            )
        }
        binding.llAppearanceSetting.setOnClickListener { showAppearanceDialog() }
        binding.llCheckUpdateSetting.setOnClickListener {
            appUpdateViewModel.checkForUpdate(isManual = true)
        }
        binding.llPlanSubscriptionSetting.setOnClickListener {
            startActivity(RoutinWebActivity.createPlanSubscriptionIntent(requireContext()))
        }
        binding.llWeeklyResetStatsSetting.setOnClickListener {
            showWeeklyResetStatsDialog()
        }
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

    /** 展示累计额度、全局历史 Reset 次数和逐 Key 金额明细。 */
    private fun showWeeklyResetStatsDialog() {
        val state = planUsageViewModel.uiState.value
        if (state.isLoadingLocalData) {
            MyToastD.show(getString(R.string.settings_weekly_reset_stats_loading))
            return
        }
        val entries = state.planKeys.mapNotNull { planKey ->
            val amount = planKey.weeklyResetStats?.totalRestoredUsd ?: return@mapNotNull null
            if (amount <= 0.0) return@mapNotNull null
            planKey.name to amount
        }
        val total = entries.sumOf { it.second }
        val totalResetCount = state.planKeys
            .mapNotNull { it.weeklyResetStats?.totalResetCount }
            .maxOrNull()
            ?: 0
        val dialogBinding = DialogWeeklyResetStatsBinding.inflate(layoutInflater)
        dialogBinding.tvTotalAmount.text = PlanUsageFormatter.formatUsd(total)
        dialogBinding.tvTotalResetCount.text = getString(
            R.string.settings_weekly_reset_stats_count_value,
            totalResetCount
        )
        if (entries.isEmpty()) {
            dialogBinding.llKeyBreakdown.visibility = View.GONE
            dialogBinding.tvEmpty.visibility = View.VISIBLE
        } else {
            dialogBinding.llKeyBreakdown.visibility = View.VISIBLE
            dialogBinding.tvEmpty.visibility = View.GONE
            entries.forEach { (name, amount) ->
                val rowBinding = ItemWeeklyResetStatsKeyBinding.inflate(
                    layoutInflater,
                    dialogBinding.llKeyBreakdown,
                    false
                )
                rowBinding.tvKeyName.text = name
                rowBinding.tvKeyAmount.text = PlanUsageFormatter.formatUsd(amount)
                dialogBinding.llKeyBreakdown.addView(rowBinding.root)
            }
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .create()
        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
        val maxDialogWidth = (resources.displayMetrics.density * 520).toInt()
        val preferredDialogWidth = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog.window?.setLayout(
            minOf(maxDialogWidth, preferredDialogWidth),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    /** 设置页可见时同步更新卡片，手动检查期间禁止重复点击。 */
    private fun observeUpdateState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appUpdateViewModel.uiState.collect { state ->
                    appUpdateCardRenderer.render(state)
                    binding.llCheckUpdateSetting.isEnabled = !state.isChecking
                }
            }
        }
    }

    /** 展示三种外观模式，单击后立即保存并重建界面。 */
    private fun showAppearanceDialog() {
        val appearanceModes = AppearanceMode.values()
        val selectedIndex = appearanceModes.indexOf(
            AppAppearancePreference.getSelectedMode(requireContext())
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.menu_appearance)
            .setSingleChoiceItems(
                appearanceModes.map { getString(it.displayNameResId) }.toTypedArray(),
                selectedIndex
            ) { dialog, which ->
                dialog.dismiss()
                AppAppearancePreference.saveAndApply(requireContext(), appearanceModes[which])
            }
            .show()
    }

    /** 将当前外观偏好映射为设置行右侧文案。 */
    private fun renderAppearanceMode() {
        val appearanceMode = AppAppearancePreference.getSelectedMode(requireContext())
        binding.tvAppearanceValue.text = getString(appearanceMode.displayNameResId)
    }

    /** 将最近一次日志同步结果映射为设置行右侧摘要，暂无数据时保留可执行提示。 */
    private fun renderRoutinRecentGroup() {
        val recentGroup = routinRecentGroupStore.load()
        binding.tvRoutinAccountValue.text = recentGroup?.let(::formatRecentGroupSummary)
            ?: getString(R.string.settings_routin_account_empty)
    }

    /**
     * 将分组、额度消耗倍率和请求时间组成摘要，以 1 倍为真实额度消耗基线应用语义色。
     * @param recentGroup 最近一次成功日志对应的分组信息
     */
    private fun formatRecentGroupSummary(recentGroup: RoutinRecentGroup): CharSequence {
        val multiplierText = getString(
            R.string.settings_routin_account_multiplier,
            formatMultiplier(recentGroup.multiplier)
        )
        val summary = getString(
            R.string.settings_routin_account_summary,
            recentGroup.groupName,
            multiplierText,
            formatRequestTime(recentGroup.requestTime)
        )
        val multiplierStart = summary.indexOf(multiplierText)
        if (multiplierStart < 0) return summary
        val multiplierColorResId = when {
            recentGroup.multiplier < STANDARD_QUOTA_MULTIPLIER -> R.color.plan_usage_success
            recentGroup.multiplier > STANDARD_QUOTA_MULTIPLIER -> R.color.plan_usage_danger
            else -> R.color.plan_usage_text_secondary
        }
        return SpannableString(summary).apply {
            setSpan(
                ForegroundColorSpan(requireContext().getColor(multiplierColorResId)),
                multiplierStart,
                multiplierStart + multiplierText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** 统一倍率显示，避免整数倍率在设置页显示成带无意义小数的文案。 */
    private fun formatMultiplier(multiplier: Double): String {
        return if (multiplier % 1.0 == 0.0) {
            multiplier.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", multiplier)
                .trimEnd('0')
                .trimEnd('.')
        }
    }

    /** 将日志页面时间压缩为设置摘要；无法解析时保留网页原文，避免丢失用户可核对信息。 */
    private fun formatRequestTime(requestTime: String): String {
        val parsedTime = runCatching {
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).apply {
                isLenient = false
            }.parse(requestTime)
        }.getOrNull() ?: return requestTime
        val now = Calendar.getInstance()
        val parsedCalendar = Calendar.getInstance().apply { time = parsedTime }
        return if (
            now.get(Calendar.ERA) == parsedCalendar.get(Calendar.ERA) &&
            now.get(Calendar.YEAR) == parsedCalendar.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == parsedCalendar.get(Calendar.DAY_OF_YEAR)
        ) {
            SimpleDateFormat("'今天' HH:mm", Locale.CHINA).format(parsedTime)
        } else {
            SimpleDateFormat("MM/dd HH:mm", Locale.US).format(parsedTime)
        }
    }

    /** 从网页返回或页面重新可见时重新读取本地结果，取消同步不会留下“正在同步”假状态。 */
    override fun onResume() {
        super.onResume()
        if (_binding != null) renderRoutinRecentGroup()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        /** 1 倍为标准额度消耗基线，小于 1 为节省，大于 1 为额外消耗。 */
        private const val STANDARD_QUOTA_MULTIPLIER = 1.0
    }
}
