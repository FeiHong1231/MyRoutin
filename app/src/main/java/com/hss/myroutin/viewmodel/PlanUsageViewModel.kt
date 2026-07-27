package com.hss.myroutin.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hss.myroutin.model.SavedPlanKey
import com.hss.myroutin.repository.PlanUsageQueryResult
import com.hss.myroutin.repository.PlanUsageRepository
import com.hss.myroutin.store.PlanUsageKeyStore
import com.hss.myroutin.store.PlanUsageKeyLoadResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 说明：订阅额度页的业务状态入口，集中管理 Key、刷新任务、持久化和一次性页面事件。
 *
 * @作者 huangssh
 * @版本 2.2
 */
class PlanUsageViewModel(application: Application) : AndroidViewModel(application) {

    /** Key 与缓存始终通过本机加密存储读写，页面层不直接接触 SharedPreferences。 */
    private val keyStore = PlanUsageKeyStore(application)

    /** 网络请求与接口 JSON 映射由 Repository 处理，ViewModel 只消费查询结果。 */
    private val repository = PlanUsageRepository()

    /** 首次读取结果保留异常语义，避免把不可解密的缓存误认为新用户的空列表。 */
    private val keyLoadResult = keyStore.loadKeys()

    /** 内存中的完整 Key 集合是排序、更新与写回存储时的唯一数据源。 */
    private val savedPlanKeys: MutableList<SavedPlanKey> = when (keyLoadResult) {
        is PlanUsageKeyLoadResult.Loaded -> keyLoadResult.keys.toMutableList()
        PlanUsageKeyLoadResult.Empty,
        PlanUsageKeyLoadResult.Unreadable -> mutableListOf()
    }

    /** 仅当前页面会话使用的刷新失败信息，不写入本机缓存。 */
    private val latestErrorByKeyId = mutableMapOf<String, String>()

    private val _uiState = MutableStateFlow(
        PlanUsageUiState(
            planKeys = sortedPlanKeys(),
            isAddKeyPanelVisible = savedPlanKeys.isEmpty(),
            localDataWarningMessage = when (keyLoadResult) {
                PlanUsageKeyLoadResult.Unreadable -> UNREADABLE_LOCAL_DATA_WARNING
                else -> null
            }
        )
    )

    /** 页面持续观察的不可变状态，旋转页面时由新的 Activity 重新渲染。 */
    val uiState: StateFlow<PlanUsageUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PlanUsageUiEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)

    /** 键盘、Toast、滚动等一次性 UI 副作用，不混入可恢复页面状态。 */
    val events: SharedFlow<PlanUsageUiEvent> = _events.asSharedFlow()

    /**
     * 展开或收起添加面板；刷新期间禁止改变输入区状态，避免新增请求与批量刷新状态交错。
     */
    fun toggleAddKeyPanel() {
        updateUiState { state ->
            if (state.isRefreshingAll) {
                state
            } else {
                state.copy(isAddKeyPanelVisible = !state.isAddKeyPanelVisible)
            }
        }
    }

    /**
     * 校验并添加一个订阅 Key；只有查询成功后才会把 Key 和缓存结果写入本机加密存储。
     * @param rawName 用户输入的 Key 名称
     * @param rawApiKey 用户输入或粘贴的原始订阅 Key
     */
    fun queryAndAddPlanKey(rawName: String, rawApiKey: String) {
        val apiKey = rawApiKey.trim()
        val state = _uiState.value
        if (state.isAddingKey || state.isRefreshingAll) {
            return
        }
        if (apiKey.isBlank()) {
            sendEvent(PlanUsageUiEvent.ShowToast("请输入 apikey"))
            return
        }
        val duplicatedKey = savedPlanKeys.firstOrNull { it.apiKey == apiKey }
        if (duplicatedKey != null) {
            updateUiState { it.copy(isAddKeyPanelVisible = false) }
            sendEvent(PlanUsageUiEvent.HideKeyboard)
            sendEvent(PlanUsageUiEvent.ShowToast("该 Key 已添加"))
            sendEvent(PlanUsageUiEvent.ScrollToPlanKey(duplicatedKey.id))
            return
        }
        updateUiState { it.copy(isAddingKey = true) }
        sendEvent(PlanUsageUiEvent.HideKeyboard)
        viewModelScope.launch {
            val usage = when (val result = repository.queryPlanUsage(apiKey, ADD_KEY_REQUEST_TRACE)) {
                is PlanUsageQueryResult.Failure -> {
                    updateUiState { it.copy(isAddingKey = false) }
                    sendEvent(PlanUsageUiEvent.ShowToast("订阅查询失败：${result.error.userMessage}"))
                    return@launch
                }
                is PlanUsageQueryResult.Success -> result.usage
            }
            val now = System.currentTimeMillis()
            val name = rawName.trim().ifBlank { "Key ${savedPlanKeys.size + 1}" }
            val addedKey = SavedPlanKey(
                id = UUID.randomUUID().toString(),
                name = name,
                apiKey = apiKey,
                createdAt = now,
                sortOrder = nextPlanKeySortOrder(),
                lastUpdatedAt = now,
                cachedStartAt = usage?.startAt,
                cachedEndAt = usage?.endAt,
                cachedDayWindowStartAt = usage?.dayWindowStartAt,
                cachedDayWindowEndAt = usage?.dayWindowEndAt,
                cachedWeekWindowStartAt = usage?.weekWindowStartAt,
                cachedWeekWindowEndAt = usage?.weekWindowEndAt,
                cachedUsage = usage
            )
            savedPlanKeys.add(addedKey)
            keyStore.saveKeys(savedPlanKeys)
            publishPlanKeys { current ->
                current.copy(
                    isAddingKey = false,
                    isAddKeyPanelVisible = false,
                    refreshStatusText = null,
                    // 新 Key 已成功加密写入，旧的异常密文已被覆盖，无需继续保留恢复提示。
                    localDataWarningMessage = null
                )
            }
            sendEvent(PlanUsageUiEvent.ClearAddKeyInputs)
            sendEvent(PlanUsageUiEvent.ScrollToPlanKey(addedKey.id))
            sendEvent(PlanUsageUiEvent.ShowToast("已添加 $name"))
        }
    }

    /**
     * 按当前排序顺序串行刷新全部 Key，避免多 Key 同时请求导致接口压力或卡片状态错位。
     */
    fun refreshAllPlanKeys() {
        val currentState = _uiState.value
        if (savedPlanKeys.isEmpty() || currentState.isRefreshingAll) {
            return
        }
        val refreshQueue = sortedPlanKeys()
        val refreshTraceId = "refresh-${System.currentTimeMillis()}"
        updateUiState {
            it.copy(
                isAddKeyPanelVisible = false,
                isRefreshingAll = true,
                refreshCurrentIndex = 0,
                refreshTotalCount = refreshQueue.size,
                refreshStatusText = null
            )
        }
        sendEvent(PlanUsageUiEvent.HideKeyboard)
        viewModelScope.launch {
            refreshQueue.forEachIndexed { index, planKey ->
                updateUiState { state ->
                    state.copy(
                        refreshCurrentIndex = index + 1,
                        refreshingKeyIds = state.refreshingKeyIds + planKey.id
                    )
                }
                val requestTrace = "[$refreshTraceId] ${index + 1}/${refreshQueue.size} ${planKey.name}"
                val result = repository.queryPlanUsage(planKey.apiKey, requestTrace)
                applyRefreshResult(planKey.id, result)
                publishPlanKeys { state ->
                    state.copy(refreshingKeyIds = state.refreshingKeyIds - planKey.id)
                }
            }
            updateUiState {
                it.copy(
                    isRefreshingAll = false,
                    refreshStatusText = "已刷新 ${refreshQueue.size} 项"
                )
            }
        }
    }

    /**
     * 切换一张 Key 卡片的详情展开状态，并立即写入本机加密存储。
     * @param keyId 需要更新的 Key ID
     */
    fun togglePlanKeyExpansion(keyId: String) {
        if (_uiState.value.isRefreshingAll) {
            return
        }
        updatePlanKey(keyId) { it.copy(isExpanded = !it.isExpanded) }
    }

    /**
     * 将 Key 在当前列表中上移或下移一位，保留其余 Key 的相对顺序。
     * @param keyId 需要移动的 Key ID
     * @param moveOffset 上移为 -1，下移为 1
     */
    fun movePlanKeyByOne(keyId: String, moveOffset: Int) {
        if (_uiState.value.isRefreshingAll) {
            return
        }
        val orderedKeys = sortedPlanKeys()
        val currentPosition = orderedKeys.indexOfFirst { it.id == keyId }
        val targetPosition = currentPosition + moveOffset
        if (currentPosition < 0 || targetPosition !in orderedKeys.indices) {
            return
        }
        val currentKey = orderedKeys[currentPosition]
        val targetKey = orderedKeys[targetPosition]
        val currentIndex = savedPlanKeys.indexOfFirst { it.id == currentKey.id }
        val targetIndex = savedPlanKeys.indexOfFirst { it.id == targetKey.id }
        if (currentIndex < 0 || targetIndex < 0) {
            return
        }
        savedPlanKeys[currentIndex] = currentKey.copy(sortOrder = targetKey.sortOrder)
        savedPlanKeys[targetIndex] = targetKey.copy(sortOrder = currentKey.sortOrder)
        keyStore.saveKeys(savedPlanKeys)
        publishPlanKeys()
    }

    /**
     * 修改已保存 Key 的展示名称；空白名称不更新，避免卡片标题失去可识别性。
     * @param keyId 需要重命名的 Key ID
     * @param rawName 用户确认后的名称
     */
    fun renamePlanKey(keyId: String, rawName: String) {
        val name = rawName.trim()
        if (name.isBlank() || _uiState.value.isRefreshingAll) {
            return
        }
        updatePlanKey(keyId) { it.copy(name = name) }
    }

    /**
     * 删除一个已保存 Key 及其缓存，并在最后一项被移除时重新显示添加面板。
     * @param keyId 需要删除的 Key ID
     */
    fun deletePlanKey(keyId: String) {
        if (_uiState.value.isRefreshingAll) {
            return
        }
        val index = savedPlanKeys.indexOfFirst { it.id == keyId }
        if (index < 0) {
            return
        }
        savedPlanKeys.removeAt(index)
        latestErrorByKeyId.remove(keyId)
        keyStore.saveKeys(savedPlanKeys)
        publishPlanKeys { state ->
            state.copy(isAddKeyPanelVisible = savedPlanKeys.isEmpty())
        }
    }

    /**
     * 根据当前已排序 Key 构建列表展示顺序，排序号异常时用创建时间保证展示稳定。
     */
    private fun sortedPlanKeys(): List<SavedPlanKey> {
        return savedPlanKeys.sortedWith(
            compareBy<SavedPlanKey> { it.sortOrder }
                .thenBy { it.createdAt }
        )
    }

    /** 新 Key 始终追加到当前最大排序号之后。 */
    private fun nextPlanKeySortOrder(): Int {
        return (savedPlanKeys.maxOfOrNull { it.sortOrder } ?: -1) + 1
    }

    /**
     * 更新一项 Key 后写入加密存储，再发布新状态，避免 UI 与本地数据出现不同步。
     * @param keyId 需要更新的 Key ID
     * @param transform 基于旧条目生成新条目的变换
     */
    private fun updatePlanKey(keyId: String, transform: (SavedPlanKey) -> SavedPlanKey) {
        val index = savedPlanKeys.indexOfFirst { it.id == keyId }
        if (index < 0) {
            return
        }
        savedPlanKeys[index] = transform(savedPlanKeys[index])
        keyStore.saveKeys(savedPlanKeys)
        publishPlanKeys()
    }

    /**
     * 成功刷新才覆盖缓存，失败时保留旧额度并记录本次页面会话内的错误提示。
     * @param keyId 本次请求对应的 Key ID
     * @param result Repository 返回的查询结果
     */
    private fun applyRefreshResult(keyId: String, result: PlanUsageQueryResult) {
        val usage = when (result) {
            is PlanUsageQueryResult.Failure -> {
                latestErrorByKeyId[keyId] = result.error.userMessage
                return
            }
            is PlanUsageQueryResult.Success -> result.usage
        }
        latestErrorByKeyId.remove(keyId)
        val index = savedPlanKeys.indexOfFirst { it.id == keyId }
        if (index < 0) {
            return
        }
        val planKey = savedPlanKeys[index]
        savedPlanKeys[index] = planKey.copy(
            lastUpdatedAt = System.currentTimeMillis(),
            cachedStartAt = usage?.startAt ?: planKey.cachedStartAt,
            cachedEndAt = usage?.endAt ?: planKey.cachedEndAt,
            cachedDayWindowStartAt = usage?.dayWindowStartAt ?: planKey.cachedDayWindowStartAt,
            cachedDayWindowEndAt = usage?.dayWindowEndAt ?: planKey.cachedDayWindowEndAt,
            cachedWeekWindowStartAt = usage?.weekWindowStartAt ?: planKey.cachedWeekWindowStartAt,
            cachedWeekWindowEndAt = usage?.weekWindowEndAt ?: planKey.cachedWeekWindowEndAt,
            cachedUsage = usage
        )
        keyStore.saveKeys(savedPlanKeys)
    }

    /** 将内存数据源和错误信息投影为可观察的页面状态。 */
    private fun publishPlanKeys(transform: (PlanUsageUiState) -> PlanUsageUiState = { it }) {
        updateUiState { state ->
            transform(state).copy(
                planKeys = sortedPlanKeys(),
                latestErrorByKeyId = latestErrorByKeyId.toMap()
            )
        }
    }

    /**
     * 统一更新状态，避免不同用户操作各自维护重复的 StateFlow 写入逻辑。
     * @param transform 基于当前状态生成下一状态
     */
    private fun updateUiState(transform: (PlanUsageUiState) -> PlanUsageUiState) {
        _uiState.update(transform)
    }

    /** 发送只应消费一次的 UI 副作用；无订阅页面时无需为历史事件保留重放值。 */
    private fun sendEvent(event: PlanUsageUiEvent) {
        _events.tryEmit(event)
    }

    private companion object {
        private const val ADD_KEY_REQUEST_TRACE = "[添加 Key]"
        private const val EVENT_BUFFER_CAPACITY = 4
        private const val UNREADABLE_LOCAL_DATA_WARNING =
            "本机加密数据无法读取，可能来自其他设备或已失效。为保护 API Key，已忽略该数据；请重新添加 Key。"
    }
}

/**
 * 说明：订阅额度页的完整渲染状态，页面只依据该对象决定可见内容和控件可操作性。
 *
 * @作者 huangssh
 * @版本 2.1
 */
data class PlanUsageUiState(
    val planKeys: List<SavedPlanKey> = emptyList(),
    val refreshingKeyIds: Set<String> = emptySet(),
    val latestErrorByKeyId: Map<String, String> = emptyMap(),
    val isAddKeyPanelVisible: Boolean = false,
    val isAddingKey: Boolean = false,
    val isRefreshingAll: Boolean = false,
    val refreshCurrentIndex: Int = 0,
    val refreshTotalCount: Int = 0,
    val refreshStatusText: String? = null,
    /** 仅在启动时读取到异常本地密文时展示，成功写入新 Key 后自动清除。 */
    val localDataWarningMessage: String? = null
)

/**
 * 说明：需要由 Activity 执行的一次性 UI 副作用，避免 Toast、键盘与滚动状态在重建后重复触发。
 *
 * @作者 huangssh
 * @版本 2.1
 */
sealed interface PlanUsageUiEvent {
    data class ShowToast(val message: String) : PlanUsageUiEvent
    data class ScrollToPlanKey(val keyId: String) : PlanUsageUiEvent
    object HideKeyboard : PlanUsageUiEvent
    object ClearAddKeyInputs : PlanUsageUiEvent
}
