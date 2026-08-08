package com.hss.myroutin.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hss.myroutin.R
import com.hss.myroutin.model.ModelRadarSnapshot
import com.hss.myroutin.repository.ModelRadarLoadError
import com.hss.myroutin.repository.ModelRadarLoadResult
import com.hss.myroutin.repository.ModelRadarRepository
import com.hss.myroutin.store.ModelRadarCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    init {
        loadCacheAndRefresh()
    }

    /** 用户手动刷新时忽略缓存有效期，但禁止并发发起重复的 3 MB 请求。 */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch { refreshRemote() }
    }

    /** 先展示紧凑缓存；只有缓存缺失或超过十分钟时才自动请求远端。 */
    private fun loadCacheAndRefresh() {
        viewModelScope.launch {
            val cachedSnapshot = withContext(Dispatchers.IO) { cacheStore.load() }
            if (cachedSnapshot != null) {
                _uiState.update { state ->
                    state.copy(
                        snapshot = cachedSnapshot,
                        isLoading = false,
                        isShowingCachedData = true
                    )
                }
            }
            if (cachedSnapshot == null || !isCacheFresh(cachedSnapshot)) {
                refreshRemote()
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /** 请求成功后写入紧凑缓存；失败时保留已有快照并给出明确状态。 */
    private suspend fun refreshRemote() {
        _uiState.update { state ->
            state.copy(
                isLoading = state.snapshot == null,
                isRefreshing = true,
                statusMessage = null
            )
        }
        when (val result = repository.load()) {
            is ModelRadarLoadResult.Success -> {
                withContext(Dispatchers.IO) { cacheStore.save(result.snapshot) }
                _uiState.update { state ->
                    state.copy(
                        snapshot = result.snapshot,
                        isLoading = false,
                        isRefreshing = false,
                        isShowingCachedData = false,
                        statusMessage = null
                    )
                }
            }

            is ModelRadarLoadResult.Failure -> {
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isShowingCachedData = state.snapshot != null,
                        statusMessage = if (state.snapshot == null) {
                            resolveLoadError(result.error)
                        } else {
                            getString(R.string.model_radar_refresh_failed_with_cache)
                        }
                    )
                }
            }
        }
    }

    /** 自动刷新窗口与站点页面的十分钟更新频率保持一致。 */
    private fun isCacheFresh(snapshot: ModelRadarSnapshot): Boolean {
        val cacheAge = System.currentTimeMillis() - snapshot.fetchedAt
        return snapshot.fetchedAt > 0L && cacheAge in 0L..CACHE_TTL_MILLIS
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
    val statusMessage: String? = null
)
