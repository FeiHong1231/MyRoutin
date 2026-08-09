package com.hss.myroutin.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hss.myroutin.R
import com.hss.myroutin.model.ModelRadarSnapshot
import com.hss.myroutin.repository.ModelRadarDiagnostics
import com.hss.myroutin.repository.ModelRadarLoadError
import com.hss.myroutin.repository.ModelRadarLoadResult
import com.hss.myroutin.repository.ModelRadarRepository
import com.hss.myroutin.store.ModelRadarCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 说明：模型雷达页面状态入口，负责缓存优先展示、过期刷新和失败降级。
 *
 * @作者 huangssh
 * @版本 3.0
 */
internal class ModelRadarViewModel(application: Application) : AndroidViewModel(application) {

    /** 公开数据缓存延迟到 IO 线程访问，避免 Activity 启动阶段读取 SharedPreferences。 */
    private val cacheStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ModelRadarCacheStore(getApplication<Application>())
    }

    /** 第三方请求和 JSON 聚合统一由 Repository 处理。 */
    private val repository = ModelRadarRepository()

    private val _uiState = MutableStateFlow(ModelRadarUiState(isLoading = true))

    /** 页面只观察不可变状态，旋转后由新 Activity 恢复当前快照。 */
    val uiState: StateFlow<ModelRadarUiState> = _uiState.asStateFlow()

    /** 刷新失败等一次性反馈通过事件通道发送，避免把错误文案固定在页面内容中。 */
    private val eventChannel = Channel<ModelRadarUiEvent>(capacity = Channel.UNLIMITED)

    /** 页面级一次性 UI 副作用，例如刷新失败 Toast。 */
    val events: Flow<ModelRadarUiEvent> = eventChannel.receiveAsFlow()

    init {
        loadCacheAndRefresh()
    }

    /** 用户手动刷新时忽略缓存有效期，但禁止并发发起重复的 3 MB 请求。 */
    fun refresh() {
        if (_uiState.value.isRefreshing) {
            ModelRadarDiagnostics.debug { "忽略重复的手动刷新请求" }
            return
        }
        ModelRadarDiagnostics.debug { "用户触发手动刷新" }
        viewModelScope.launch { refreshRemote() }
    }

    /**
     * 在运行时缓存和 APK 内置快照中选择更新时间较新的数据，再按缓存时效刷新远端。
     */
    private fun loadCacheAndRefresh() {
        viewModelScope.launch {
            val (cachedSnapshot, bundledSnapshot) = withContext(Dispatchers.IO) {
                cacheStore.load() to cacheStore.loadBundled()
            }
            val isBundledNewer = bundledSnapshot != null &&
                (cachedSnapshot == null || bundledSnapshot.fetchedAt > cachedSnapshot.fetchedAt)
            val localSnapshot = if (isBundledNewer) bundledSnapshot else cachedSnapshot
            val localSource = if (isBundledNewer) "内置快照" else "缓存"
            if (localSnapshot != null) {
                ModelRadarDiagnostics.logSnapshot(localSource, localSnapshot)
                _uiState.update { state ->
                    state.copy(
                        snapshot = localSnapshot,
                        isLoading = false,
                        isShowingCachedData = true
                    )
                }
            } else {
                ModelRadarDiagnostics.debug { "缓存和内置快照均未命中或解析失败" }
            }
            if (cachedSnapshot == null || isBundledNewer || !isCacheFresh(cachedSnapshot)) {
                ModelRadarDiagnostics.debug {
                    "运行时缓存不可用、落后于内置快照或已过期，开始远端刷新"
                }
                refreshRemote()
            } else {
                ModelRadarDiagnostics.debug { "缓存仍在有效期内，本次不请求远端" }
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /** 请求成功后写入紧凑缓存；失败时保留已有快照并通过一次性事件提示用户。 */
    private suspend fun refreshRemote() {
        ModelRadarDiagnostics.debug {
            "远端刷新开始，当前是否有可降级快照=${_uiState.value.snapshot != null}"
        }
        _uiState.update { state ->
            state.copy(
                isLoading = state.snapshot == null,
                isRefreshing = true,
                isLoadFailed = false
            )
        }
        when (val result = repository.load()) {
            is ModelRadarLoadResult.Success -> {
                withContext(Dispatchers.IO) { cacheStore.save(result.snapshot) }
                ModelRadarDiagnostics.debug { "远端快照已写入缓存" }
                _uiState.update { state ->
                    state.copy(
                        snapshot = result.snapshot,
                        isLoading = false,
                        isRefreshing = false,
                        isShowingCachedData = false,
                        isLoadFailed = false
                    )
                }
            }

            is ModelRadarLoadResult.Failure -> {
                val currentState = _uiState.value
                ModelRadarDiagnostics.debug {
                    "远端刷新失败，error=${result.error.debugDescription()}，" +
                        "继续展示缓存=${currentState.snapshot != null}"
                }
                val failureMessage = if (currentState.snapshot == null) {
                    resolveLoadError(result.error)
                } else {
                    getString(R.string.model_radar_refresh_failed_with_cache)
                }
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isShowingCachedData = state.snapshot != null,
                        isLoadFailed = true
                    )
                }
                sendEvent(ModelRadarUiEvent.ShowToast(failureMessage))
            }
        }
    }

    /** 自动刷新窗口与站点页面的十分钟更新频率保持一致。 */
    private fun isCacheFresh(snapshot: ModelRadarSnapshot): Boolean {
        val cacheAge = System.currentTimeMillis() - snapshot.fetchedAt
        val isFresh = snapshot.fetchedAt > 0L && cacheAge in 0L..CACHE_TTL_MILLIS
        ModelRadarDiagnostics.debug {
            "缓存有效期检查：age=${cacheAge}ms，ttl=${CACHE_TTL_MILLIS}ms，isFresh=$isFresh"
        }
        return isFresh
    }

    /** 将稳定错误类型转换为仅供 Debug 日志查看的简短描述。 */
    private fun ModelRadarLoadError.debugDescription(): String {
        return when (this) {
            is ModelRadarLoadError.Http -> "Http($responseCode)"
            ModelRadarLoadError.NetworkTimeout -> "NetworkTimeout"
            ModelRadarLoadError.NetworkUnavailable -> "NetworkUnavailable"
            ModelRadarLoadError.InvalidResponse -> "InvalidResponse"
            ModelRadarLoadError.Unknown -> "Unknown"
        }
    }

    /** 将底层失败映射为本地化文案，禁止把第三方响应内容直接显示给用户。 */
    private fun resolveLoadError(error: ModelRadarLoadError): String {
        return when (error) {
            is ModelRadarLoadError.Http -> getString(
                R.string.model_radar_error_http,
                error.responseCode
            )
            ModelRadarLoadError.NetworkTimeout -> getString(R.string.model_radar_error_timeout)
            ModelRadarLoadError.NetworkUnavailable -> getString(R.string.model_radar_error_network)
            ModelRadarLoadError.InvalidResponse -> getString(R.string.model_radar_error_invalid_response)
            ModelRadarLoadError.Unknown -> getString(R.string.model_radar_error_unknown)
        }
    }

    private fun getString(@StringRes stringResId: Int, vararg formatArgs: Any): String {
        return getApplication<Application>().getString(stringResId, *formatArgs)
    }

    /** 将一次性页面反馈写入通道，避免刷新失败信息进入可恢复页面状态。 */
    private fun sendEvent(event: ModelRadarUiEvent) {
        eventChannel.trySend(event)
    }

    private companion object {
        private const val CACHE_TTL_MILLIS = 10 * 60 * 1_000L
    }
}

/**
 * 说明：模型雷达完整渲染状态，缓存快照和刷新状态分离，刷新时页面不会清空。
 *
 * @作者 huangssh
 * @版本 3.0
 */
internal data class ModelRadarUiState(
    val snapshot: ModelRadarSnapshot? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isShowingCachedData: Boolean = false,
    /** 请求失败时隐藏空状态文案，避免刷新失败同时出现页面内提示。 */
    val isLoadFailed: Boolean = false
)

/**
 * 说明：模型雷达页面的一次性 UI 副作用，避免 Toast 被状态重放时重复展示。
 *
 * @作者 huangssh
 * @版本 3.0
 */
internal sealed interface ModelRadarUiEvent {
    data class ShowToast(val message: String) : ModelRadarUiEvent
}
