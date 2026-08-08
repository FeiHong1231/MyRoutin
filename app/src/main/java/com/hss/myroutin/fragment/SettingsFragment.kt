package com.hss.myroutin.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.hss.myroutin.appearance.AppAppearancePreference
import com.hss.myroutin.appearance.AppearanceMode
import com.hss.myroutin.databinding.FragmentSettingsBinding
import com.hss.myroutin.update.AppUpdateViewModel
import kotlinx.coroutines.launch

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

    /** 更新卡片复用用量页的状态映射，设置页只提供入口和容器。 */
    private lateinit var appUpdateCardRenderer: AppUpdateCardRenderer

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
        binding.tvCurrentVersion.text = getString(
            R.string.settings_current_version,
            BuildConfig.VERSION_NAME
        )
        binding.llAppearanceSetting.setOnClickListener { showAppearanceDialog() }
        binding.llCheckUpdateSetting.setOnClickListener {
            appUpdateViewModel.checkForUpdate(isManual = true)
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

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
