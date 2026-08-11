package com.hss.myroutin.update

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hss.myroutin.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 说明：前台更新状态入口，负责检查、下载、取消、校验结果与安装提示，页面只负责渲染。
 *
 * @作者 huangssh
 * @版本 2.2
 */
class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {

    /** 更新清单与 APK 下载由 Repository 处理，ViewModel 不直接访问网络或文件流。 */
    private val repository = AppUpdateRepository(application)

    /** 当前检查任务用于避免用户连续点击“检查更新”产生重复网络请求。 */
    private var checkUpdateJob: Job? = null

    /** 检查序号用于忽略已取消旧请求的迟到结果，避免覆盖用户刚发起的手动检查状态。 */
    private var checkRequestToken = 0L

    /** 下载任务只允许存在一个，页面进入后台时会自动暂停而不会继续后台下载。 */
    private var downloadUpdateJob: Job? = null

    private val _uiState = MutableStateFlow(AppUpdateUiState())

    /** 更新卡片的持续状态，旋转屏幕后可重新渲染当前进度与安装入口。 */
    val uiState: StateFlow<AppUpdateUiState> = _uiState.asStateFlow()

    /** 页面重建期间暂存安装提示和反馈，避免下载恰好完成时丢失一次性事件。 */
    private val eventChannel = Channel<AppUpdateUiEvent>(capacity = Channel.UNLIMITED)

    /** 安装提示与手动检查反馈只由一个前台页面消费，旋转后不会重复处理。 */
    val events: Flow<AppUpdateUiEvent> = eventChannel.receiveAsFlow()

    init {
        checkForUpdate(isManual = false)
    }

    /**
     * 检查 GitHub 最新稳定版本；启动检查静默失败，手动检查会反馈可行动结果。
     * @param isManual 是否由用户点击“检查更新”触发
     */
    fun checkForUpdate(isManual: Boolean) {
        val currentCardState = _uiState.value.cardState
        if (currentCardState is AppUpdateCardState.Downloading) {
            if (isManual) {
                sendEvent(AppUpdateUiEvent.ShowToast(getString(R.string.update_downloading_toast)))
            }
            return
        }
        if (currentCardState is AppUpdateCardState.Downloaded) {
            if (isManual) {
                sendEvent(AppUpdateUiEvent.ShowToast(getString(R.string.update_downloaded_toast)))
            }
            return
        }
        if (_uiState.value.isChecking && !isManual) {
            return
        }
        // 手动点击应接管启动时的静默请求，确保用户最终能得到成功或失败反馈。
        if (_uiState.value.isChecking) {
            repository.cancelUpdateCheck()
        }
        checkUpdateJob?.cancel()
        updateUiState { it.copy(isChecking = true, isManualChecking = isManual) }
        val requestToken = ++checkRequestToken
        checkUpdateJob = viewModelScope.launch {
            val result = repository.checkForUpdate()
            if (requestToken != checkRequestToken) {
                return@launch
            }
            when (result) {
                AppUpdateCheckResult.NoUpdate -> {
                    updateUiState { state ->
                        state.copy(
                            isChecking = false,
                            isManualChecking = false,
                            cardState = when (state.cardState) {
                                is AppUpdateCardState.Available,
                                is AppUpdateCardState.DownloadFailed -> AppUpdateCardState.Hidden
                                else -> state.cardState
                            }
                        )
                    }
                    if (isManual) {
                        sendEvent(AppUpdateUiEvent.ShowToast(getString(R.string.update_latest_toast)))
                    }
                }

                is AppUpdateCheckResult.UpdateAvailable -> {
                    updateUiState {
                        it.copy(
                            isChecking = false,
                            isManualChecking = false,
                            cardState = AppUpdateCardState.Available(result.update)
                        )
                    }
                }

                AppUpdateCheckResult.Failure -> {
                    updateUiState { it.copy(isChecking = false, isManualChecking = false) }
                    if (isManual) {
                        sendEvent(AppUpdateUiEvent.ShowToast(getString(R.string.update_check_failed_toast)))
                    }
                }
            }
        }
    }

    /** 从可下载、暂停或下载失败状态开始前台下载，重复点击不会创建并发任务。 */
    fun downloadUpdate() {
        val currentCardState = _uiState.value.cardState
        val update = when (currentCardState) {
            is AppUpdateCardState.Available -> currentCardState.update
            is AppUpdateCardState.Paused -> currentCardState.update
            is AppUpdateCardState.DownloadFailed -> currentCardState.update
            else -> return
        }
        // 主动暂停沿用内存进度，失败重试或进程重建则从磁盘分片恢复，避免圆环回退到 0%。
        val initialProgress = (currentCardState as? AppUpdateCardState.Paused)?.progress
            ?: repository.readCachedDownloadProgress(update)
        downloadUpdateJob?.cancel()
        updateUiState {
            it.copy(
                cardState = AppUpdateCardState.Downloading(
                    update = update,
                    progress = initialProgress
                )
            )
        }
        downloadUpdateJob = viewModelScope.launch {
            when (
                val result = repository.downloadUpdate(update) { progress ->
                    updateUiState { state ->
                        val downloadingState = state.cardState as? AppUpdateCardState.Downloading
                        if (downloadingState?.update == update) {
                            state.copy(cardState = downloadingState.copy(progress = progress))
                        } else {
                            state
                        }
                    }
                }
            ) {
                is AppUpdateDownloadResult.Success -> {
                    updateUiState {
                        it.copy(cardState = AppUpdateCardState.Downloaded(update, result.apkFile))
                    }
                    sendEvent(AppUpdateUiEvent.ShowInstallPrompt(update, result.apkFile))
                }

                is AppUpdateDownloadResult.Failure -> {
                    updateUiState {
                        it.copy(
                            cardState = AppUpdateCardState.DownloadFailed(
                                update,
                                resolveDownloadFailureMessage(result.reason)
                            )
                        )
                    }
                }

                is AppUpdateDownloadResult.Paused -> {
                    updateUiState { state ->
                        when (val cardState = state.cardState) {
                            is AppUpdateCardState.Downloading -> {
                                if (cardState.update == update) {
                                    state.copy(cardState = AppUpdateCardState.Paused(update, result.progress))
                                } else {
                                    state
                                }
                            }

                            is AppUpdateCardState.Paused -> {
                                if (cardState.update == update) {
                                    state.copy(cardState = cardState.copy(progress = result.progress))
                                } else {
                                    state
                                }
                            }

                            else -> state
                        }
                    }
                }
            }
        }
    }

    /** 切换下载的暂停与继续状态，暂停保留当前临时文件，继续通过 Repository 发起断点请求。 */
    fun toggleDownloadPause() {
        when (val cardState = _uiState.value.cardState) {
            is AppUpdateCardState.Downloading -> {
                if (downloadUpdateJob?.isActive != true) {
                    return
                }
                repository.pauseForegroundDownload()
                updateUiState {
                    it.copy(cardState = AppUpdateCardState.Paused(cardState.update, cardState.progress))
                }
            }

            is AppUpdateCardState.Paused -> downloadUpdate()
            else -> Unit
        }
    }

    /** 用户主动关闭下载卡片时取消任务，并回收已下载、暂停或未完成的临时 APK。 */
    fun dismissUpdateCard() {
        if (_uiState.value.isChecking) {
            repository.cancelUpdateCheck()
            checkUpdateJob?.cancel()
            checkRequestToken++
        }
        when (val cardState = _uiState.value.cardState) {
            is AppUpdateCardState.Downloading -> {
                repository.cancelForegroundDownload()
                downloadUpdateJob?.cancel()
            }

            is AppUpdateCardState.Paused -> {
                repository.cancelForegroundDownload()
                downloadUpdateJob?.cancel()
                repository.deletePartialUpdate(cardState.update)
            }

            is AppUpdateCardState.Downloaded -> repository.deleteCachedUpdate(cardState.apkFile)
            else -> Unit
        }
        updateUiState {
            it.copy(
                isChecking = false,
                isManualChecking = false,
                cardState = AppUpdateCardState.Hidden
            )
        }
    }

    /** 页面进入后台时自动暂停并保留分片，息屏返回后可从原进度继续下载。 */
    fun stopForegroundDownload() {
        val downloadingState = _uiState.value.cardState as? AppUpdateCardState.Downloading ?: return
        if (downloadUpdateJob?.isActive != true) {
            return
        }
        repository.pauseForegroundDownload()
        updateUiState {
            it.copy(
                cardState = AppUpdateCardState.Paused(
                    downloadingState.update,
                    downloadingState.progress
                )
            )
        }
    }

    /** 将一次性事件写入缓冲通道，页面短暂重建时继续保留待处理事件。 */
    private fun sendEvent(event: AppUpdateUiEvent) {
        eventChannel.trySend(event)
    }

    /** 所有状态更新经由同一个入口，保证下载回调与检查结果不会覆盖彼此的新状态。 */
    private fun updateUiState(transform: (AppUpdateUiState) -> AppUpdateUiState) {
        _uiState.update(transform)
    }

    /**
     * 将下载层稳定失败分类映射为本地化文案，卡片状态无需依赖异常类型。
     * @param reason APK 下载失败原因
     * @return 可直接展示在更新卡片中的文案
     */
    private fun resolveDownloadFailureMessage(reason: AppUpdateDownloadFailureReason): String {
        val stringResId = when (reason) {
            AppUpdateDownloadFailureReason.DIRECTORY_UNAVAILABLE -> R.string.update_failure_directory
            AppUpdateDownloadFailureReason.INTEGRITY_CHECK_FAILED -> R.string.update_failure_integrity
            AppUpdateDownloadFailureReason.TIMEOUT -> R.string.update_failure_timeout
            AppUpdateDownloadFailureReason.NETWORK -> R.string.update_failure_network
            AppUpdateDownloadFailureReason.UNKNOWN -> R.string.update_failure_unknown
        }
        return getString(stringResId)
    }

    /**
     * 统一读取字符串资源，避免更新状态分支重复获取 Application。
     * @param stringResId 字符串资源 ID
     * @return 当前语言环境下的最终文案
     */
    private fun getString(@StringRes stringResId: Int): String {
        return getApplication<Application>().getString(stringResId)
    }

    override fun onCleared() {
        repository.cancelUpdateCheck()
        repository.cancelForegroundDownload()
        super.onCleared()
    }

}

/**
 * 说明：更新卡片持续状态，空闲时不占首页空间，下载过程与安装入口均由同一张卡片承载。
 *
 * @作者 huangssh
 * @版本 2.2
 */
data class AppUpdateUiState(
    val isChecking: Boolean = false,
    /** 仅手动检查显示短暂卡片，启动时静默检查不打断用户浏览首页。 */
    val isManualChecking: Boolean = false,
    val cardState: AppUpdateCardState = AppUpdateCardState.Hidden
)

/**
 * 说明：更新卡片的可视状态，前台下载被取消或失败后始终保留可重新下载的版本信息。
 *
 * @作者 huangssh
 * @版本 2.2
 */
sealed interface AppUpdateCardState {

    /** 当前无可展示更新，卡片从布局中隐藏。 */
    object Hidden : AppUpdateCardState

    /** 发现更高版本，等待用户明确点击下载。 */
    data class Available(val update: AppUpdateManifest) : AppUpdateCardState

    /** 仅在首页前台显示的实时下载进度。 */
    data class Downloading(
        val update: AppUpdateManifest,
        val progress: UpdateDownloadProgress
    ) : AppUpdateCardState

    /** 用户主动暂停后保留临时文件，继续下载时使用 HTTP Range 从已下载位置恢复。 */
    data class Paused(
        val update: AppUpdateManifest,
        val progress: UpdateDownloadProgress
    ) : AppUpdateCardState

    /** 网络或校验失败后显示重试入口，不丢失本次发现的更新版本。 */
    data class DownloadFailed(
        val update: AppUpdateManifest,
        val userMessage: String
    ) : AppUpdateCardState

    /** APK 已下载并通过摘要校验，等待用户主动发起系统安装。 */
    data class Downloaded(
        val update: AppUpdateManifest,
        val apkFile: File
    ) : AppUpdateCardState
}

/**
 * 说明：更新页面的一次性事件，安装页和 Toast 必须由 Activity 在当前前台上下文处理。
 *
 * @作者 huangssh
 * @版本 2.2
 */
sealed interface AppUpdateUiEvent {

    /** 向用户展示一次性轻提示。 */
    data class ShowToast(val message: String) : AppUpdateUiEvent

    /** 下载校验完成后提示用户是否立即进入系统安装页。 */
    data class ShowInstallPrompt(
        val update: AppUpdateManifest,
        val apkFile: File
    ) : AppUpdateUiEvent
}
