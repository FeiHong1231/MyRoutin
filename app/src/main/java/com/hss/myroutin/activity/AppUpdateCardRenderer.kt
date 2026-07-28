package com.hss.myroutin.activity

import android.view.View
import com.hss.myroutin.R
import com.hss.myroutin.databinding.ActivityPlanUsageInputBinding
import com.hss.myroutin.update.AppUpdateCardState
import com.hss.myroutin.update.AppUpdateManifest
import com.hss.myroutin.update.AppUpdateUiState
import com.hss.myroutin.update.UpdateDownloadProgress

/**
 * 说明：首页更新卡片渲染器，统一处理检查、下载、暂停、失败和已下载状态的控件展示。
 *
 * @param binding 查询页内更新卡片控件的 ViewBinding
 * @作者 huangssh
 * @版本 2.3
 */
internal class AppUpdateCardRenderer(
    private val binding: ActivityPlanUsageInputBinding
) {

    /**
     * 依据更新状态显示或隐藏首页下载卡片；环形进度只在应用停留前台时出现。
     * @param state 当前更新检查、下载或安装入口状态
     */
    fun render(state: AppUpdateUiState) {
        if (state.isChecking && state.isManualChecking && state.cardState is AppUpdateCardState.Hidden) {
            showCheckingCard()
            return
        }
        when (val cardState = state.cardState) {
            AppUpdateCardState.Hidden -> {
                binding.ucpUpdateProgress.isIndeterminate = false
                binding.llUpdateCard.visibility = View.GONE
            }

            is AppUpdateCardState.Available -> {
                showActionCard(
                    title = binding.root.context.getString(
                        R.string.update_available_title,
                        formatVersion(cardState.update.versionName)
                    ),
                    detail = binding.root.context.getString(R.string.update_ready_to_download),
                    actionText = binding.root.context.getString(R.string.action_download_update)
                )
            }

            is AppUpdateCardState.Downloading -> {
                showDownloadProgress(cardState.update, cardState.progress, isPaused = false)
            }

            is AppUpdateCardState.Paused -> {
                showDownloadProgress(cardState.update, cardState.progress, isPaused = true)
            }

            is AppUpdateCardState.DownloadFailed -> {
                showActionCard(
                    title = binding.root.context.getString(R.string.update_download_failed_title),
                    detail = cardState.userMessage,
                    actionText = binding.root.context.getString(R.string.action_retry)
                )
            }

            is AppUpdateCardState.Downloaded -> {
                showActionCard(
                    title = binding.root.context.getString(
                        R.string.update_downloaded_title,
                        formatVersion(cardState.update.versionName)
                    ),
                    detail = binding.root.context.getString(R.string.update_verified),
                    actionText = binding.root.context.getString(R.string.action_install_now)
                )
            }
        }
    }

    /**
     * 统一为远端版本号补充 v 前缀，供更新卡片和 Activity 安装确认弹窗共用。
     * @param versionName 服务端发布清单中的版本号
     * @return 仅包含一个 v 前缀的展示版本号
     */
    fun formatVersion(versionName: String): String {
        return if (versionName.startsWith("v", ignoreCase = true)) {
            versionName
        } else {
            binding.root.context.getString(R.string.update_version_prefixed, versionName)
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
            binding.root.context.getString(
                R.string.update_paused_title,
                formatVersion(update.versionName)
            )
        } else if (downloadPercent == null) {
            binding.root.context.getString(
                R.string.update_downloading_title,
                formatVersion(update.versionName)
            )
        } else {
            binding.root.context.getString(
                R.string.update_downloading_title_with_progress,
                formatVersion(update.versionName),
                downloadPercent
            )
        }
        binding.tvUpdateDetail.text = formatDownloadProgress(progress)
        binding.flUpdateProgress.visibility = View.VISIBLE
        binding.btnUpdateAction.visibility = View.GONE
        binding.btnToggleUpdateDownload.visibility = View.VISIBLE
        binding.btnToggleUpdateDownload.setImageResource(
            if (isPaused) R.drawable.ic_update_float_play else R.drawable.ic_update_float_pause
        )
        binding.btnToggleUpdateDownload.contentDescription = binding.root.context.getString(
            if (isPaused) R.string.update_continue_download else R.string.update_pause_download
        )
        binding.btnDismissUpdate.contentDescription = binding.root.context.getString(
            if (isPaused) R.string.update_close_prompt else R.string.update_cancel_download
        )
        binding.ucpUpdateProgress.isIndeterminate = false
        binding.ucpUpdateProgress.progress = downloadPercent ?: 0
    }

    /** 手动检查时明确展示当前请求状态，避免用户只看到“正在处理”却不知道请求是否仍在进行。 */
    private fun showCheckingCard() {
        binding.llUpdateCard.visibility = View.VISIBLE
        binding.tvUpdateTitle.text = binding.root.context.getString(R.string.update_checking_title)
        binding.tvUpdateDetail.text = binding.root.context.getString(R.string.update_checking_detail)
        binding.flUpdateProgress.visibility = View.VISIBLE
        binding.ucpUpdateProgress.progress = 0
        binding.ucpUpdateProgress.isIndeterminate = true
        binding.btnUpdateAction.visibility = View.GONE
        binding.btnToggleUpdateDownload.visibility = View.GONE
        binding.btnDismissUpdate.contentDescription =
            binding.root.context.getString(R.string.update_cancel_checking)
    }

    /**
     * 渲染可点击操作的更新卡片，下载完成、待下载和失败重试共用同一套视觉结构。
     * @param title 卡片主标题
     * @param detail 卡片辅助说明
     * @param actionText 右侧操作按钮文案
     */
    private fun showActionCard(title: String, detail: String, actionText: String) {
        binding.llUpdateCard.visibility = View.VISIBLE
        binding.tvUpdateTitle.text = title
        binding.tvUpdateDetail.text = detail
        binding.flUpdateProgress.visibility = View.GONE
        binding.ucpUpdateProgress.isIndeterminate = false
        binding.btnUpdateAction.visibility = View.VISIBLE
        binding.btnToggleUpdateDownload.visibility = View.GONE
        binding.btnUpdateAction.text = actionText
        binding.btnDismissUpdate.contentDescription =
            binding.root.context.getString(R.string.update_close_prompt)
    }

    /**
     * 将字节数以可读单位展示，不足 1 MB 时保留 KB，方便用户判断下载是否正常推进。
     * @param bytes 已下载或总计的字节数
     * @return 适合下载卡片展示的文件大小
     */
    private fun formatDataSize(bytes: Long): String {
        return if (bytes < BYTES_PER_MEGABYTE) {
            binding.root.context.getString(R.string.update_size_kb, bytes / BYTES_PER_KILOBYTE)
        } else {
            binding.root.context.getString(
                R.string.update_size_mb,
                bytes / BYTES_PER_MEGABYTE.toDouble()
            )
        }
    }

    /**
     * 组合实时下载量和远端文件大小；服务端未返回大小时仍展示已下载字节数。
     * @param progress 当前下载进度
     * @return 下载卡片的辅助文案
     */
    private fun formatDownloadProgress(progress: UpdateDownloadProgress): String {
        return progress.totalBytes?.let { totalBytes ->
            binding.root.context.getString(
                R.string.update_download_progress,
                formatDataSize(progress.downloadedBytes),
                formatDataSize(totalBytes)
            )
        } ?: binding.root.context.getString(
            R.string.update_downloaded_size,
            formatDataSize(progress.downloadedBytes)
        )
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

    private companion object {
        /** 环形进度使用系统百分制，下载字节比例在写入前统一限制边界。 */
        private const val UPDATE_PROGRESS_MAX = 100
        /** 下载大小文案统一按 1024 进位，保持原有 KB 和 MB 展示口径。 */
        private const val BYTES_PER_KILOBYTE = 1024L
        private const val BYTES_PER_MEGABYTE = BYTES_PER_KILOBYTE * BYTES_PER_KILOBYTE
    }
}
