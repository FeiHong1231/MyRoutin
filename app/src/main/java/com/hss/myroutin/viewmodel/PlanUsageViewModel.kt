package com.hss.myroutin.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hss.myroutin.logic.PlanUsageCachePolicy
import com.hss.myroutin.model.SavedPlanKey
import com.hss.myroutin.repository.PlanUsageQueryError
import com.hss.myroutin.repository.PlanUsageQueryResult
import com.hss.myroutin.repository.PlanUsageRepository
import com.hss.myroutin.store.PlanUsageKeyStore
import com.hss.myroutin.store.PlanUsageKeyLoadResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 说明：订阅额度页的业务状态入口，集中管理 Key、刷新任务、持久化和一次性页面事件。
 *
 * @作者 huangssh
 * @版本 2.3
 */
class PlanUsageViewModel(application: Application) : AndroidViewModel(application) {

    /** 延迟到 IO 线程首次访问时创建存储，避免初始化 SharedPreferences 时占用页面启动主线程。 */
    private val keyStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlanUsageKeyStore(getApplication<Application>())
    }

    /** 网络请求与接口 JSON 映射由 Repository 处理，ViewModel 只消费查询结果。 */
    private val repository = PlanUsageRepository()

    /** 内存中的完整 Key 集合是排序、更新与写回存储时的唯一数据源。 */
    private val savedPlanKeys = mutableListOf<SavedPlanKey>()

    /** 仅当前页面会话使用的刷新失败信息，不写入本机缓存。 */
    private val latestErrorByKeyId = mutableMapOf<String, String>()

    /** 保存任务串行消费最新列表快照，避免连续操作并发加密或让旧数据晚于新数据落盘。 */
    private var planKeysPersistenceJob: Job? = null

    /** 保存期间出现的新快照会覆盖尚未处理的旧快照，用于合并短时间内的重复整表写入。 */
    private var pendingPlanKeysSnapshot: List<SavedPlanKey>? = null

    private val _uiState = MutableStateFlow(PlanUsageUiState(isLoadingLocalData = true))

    /** 页面持续观察的不可变状态，旋转页面时由新的 Activity 重新渲染。 */
    val uiState: StateFlow<PlanUsageUiState> = _uiState.asStateFlow()

    /** 页面重建期间暂存一次性副作用，确保查询完成事件由下一个前台页面消费一次。 */
    private val eventChannel = Channel<PlanUsageUiEvent>(capacity = Channel.UNLIMITED)

    /** 键盘、Toast、滚动等一次性 UI 副作用，不混入可恢复页面状态。 */
    val events: Flow<PlanUsageUiEvent> = eventChannel.receiveAsFlow()

    init {
        loadSavedPlanKeys()
    }

    /**
     * 展开或收起添加面板；刷新期间禁止改变输入区状态，避免新增请求与批量刷新状态交错。
     */
    fun toggleAddKeyPanel() {
        updateUiState { state ->
            if (state.isLoadingLocalData || state.isRefreshingAll) {
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
        if (state.isLoadingLocalData || state.isAddingKey || state.isRefreshingAll) {
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
            val result = repository.queryPlanUsage(apiKey, ADD_KEY_REQUEST_TRACE)
            if (result is PlanUsageQueryResult.Failure) {
                updateUiState { it.copy(isAddingKey = false) }
                sendEvent(PlanUsageUiEvent.ShowToast("订阅查询失败：${result.error.userMessage}"))
                return@launch
            }
            val now = System.currentTimeMillis()
            val name = rawName.trim().ifBlank { "Key ${savedPlanKeys.size + 1}" }
            val newPlanKey = SavedPlanKey(
                id = UUID.randomUUID().toString(),
                name = name,
                apiKey = apiKey,
                createdAt = now,
                sortOrder = nextPlanKeySortOrder()
            )
            val addedKey = when (result) {
                is PlanUsageQueryResult.Available -> PlanUsageCachePolicy.applyAvailableUsage(
                    planKey = newPlanKey,
                    usage = result.usage,
                    checkedAt = now
                )
                PlanUsageQueryResult.Expired -> PlanUsageCachePolicy.applyExpired(newPlanKey, now)
                is PlanUsageQueryResult.Failure -> return@launch
            }
            savedPlanKeys.add(addedKey)
            schedulePlanKeysPersistence()
            publishPlanKeys { current ->
                current.copy(
                    isAddingKey = false,
                    isAddKeyPanelVisible = false,
                    refreshStatusText = null,
                    // 新 Key 已提交加密保存，后续保存任务会用最新快照覆盖旧的异常密文。
                    localDataWarningMessage = null
                )
            }
            sendEvent(PlanUsageUiEvent.ClearAddKeyInputs)
            sendEvent(PlanUsageUiEvent.ScrollToPlanKey(addedKey.id))
            val addedMessage = when (result) {
                is PlanUsageQueryResult.Available -> "已添加 $name"
                PlanUsageQueryResult.Expired -> "已添加 $name，订阅已过期"
                is PlanUsageQueryResult.Failure -> return@launch
            }
            sendEvent(PlanUsageUiEvent.ShowToast(addedMessage))
        }
    }

    /**
     * 按当前排序顺序串行刷新全部 Key；添加请求期间拒绝刷新，避免两个任务交错修改列表。
     */
    fun refreshAllPlanKeys() {
        val currentState = _uiState.value
        if (
            currentState.isLoadingLocalData ||
            currentState.isAddingKey ||
            savedPlanKeys.isEmpty() ||
            currentState.isRefreshingAll
        ) {
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
            // 有效快照、过期和 401 都是确定结果；其他失败提示仍只保留在当前页面会话。
            var hasPersistentChanges = false
            try {
                refreshQueue.forEachIndexed { index, planKey ->
                    updateUiState { state ->
                        state.copy(
                            refreshCurrentIndex = index + 1,
                            refreshingKeyIds = state.refreshingKeyIds + planKey.id
                        )
                    }
                    val requestTrace = "[$refreshTraceId] ${index + 1}/${refreshQueue.size} ${planKey.name}"
                    val result = repository.queryPlanUsage(planKey.apiKey, requestTrace)
                    hasPersistentChanges = applyRefreshResult(planKey.id, result) || hasPersistentChanges
                    publishPlanKeys { state ->
                        state.copy(refreshingKeyIds = state.refreshingKeyIds - planKey.id)
                    }
                }
            } finally {
                if (hasPersistentChanges) {
                    // 批量刷新只提交最终列表快照；任务中断时也保留已经成功刷新的数据。
                    schedulePlanKeysPersistence()
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
     * 切换一张 Key 卡片的详情展开状态，并立即提交本机加密保存任务。
     * @param keyId 需要更新的 Key ID
     */
    fun togglePlanKeyExpansion(keyId: String) {
        if (_uiState.value.isLoadingLocalData || _uiState.value.isRefreshingAll) {
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
        if (_uiState.value.isLoadingLocalData || _uiState.value.isRefreshingAll) {
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
        schedulePlanKeysPersistence()
        publishPlanKeys()
    }

    /**
     * 修改已保存 Key 的展示名称；空白名称不更新，避免卡片标题失去可识别性。
     * @param keyId 需要重命名的 Key ID
     * @param rawName 用户确认后的名称
     */
    fun renamePlanKey(keyId: String, rawName: String) {
        val name = rawName.trim()
        if (name.isBlank() || _uiState.value.isLoadingLocalData || _uiState.value.isRefreshingAll) {
            return
        }
        updatePlanKey(keyId) { it.copy(name = name) }
    }

    /**
     * 删除一个已保存 Key 及其缓存，并在最后一项被移除时重新显示添加面板。
     * @param keyId 需要删除的 Key ID
     */
    fun deletePlanKey(keyId: String) {
        if (_uiState.value.isLoadingLocalData || _uiState.value.isRefreshingAll) {
            return
        }
        val index = savedPlanKeys.indexOfFirst { it.id == keyId }
        if (index < 0) {
            return
        }
        savedPlanKeys.removeAt(index)
        latestErrorByKeyId.remove(keyId)
        schedulePlanKeysPersistence()
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
     * 更新一项 Key 后发布新状态，并将完整快照交给串行 IO 任务加密保存。
     * @param keyId 需要更新的 Key ID
     * @param transform 基于旧条目生成新条目的变换
     */
    private fun updatePlanKey(keyId: String, transform: (SavedPlanKey) -> SavedPlanKey) {
        val index = savedPlanKeys.indexOfFirst { it.id == keyId }
        if (index < 0) {
            return
        }
        savedPlanKeys[index] = transform(savedPlanKeys[index])
        schedulePlanKeysPersistence()
        publishPlanKeys()
    }

    /**
     * 应用有效快照、过期或 Key 失效结果；临时请求失败不修改持久状态和历史额度。
     * @param keyId 本次请求对应的 Key ID
     * @param result Repository 返回的查询结果
     * @return 是否产生了需要写入本机缓存的数据变化
     */
    private fun applyRefreshResult(keyId: String, result: PlanUsageQueryResult): Boolean {
        if (result is PlanUsageQueryResult.Failure && result.error !is PlanUsageQueryError.InvalidApiKey) {
            latestErrorByKeyId[keyId] = result.error.userMessage
            return false
        }
        latestErrorByKeyId.remove(keyId)
        val index = savedPlanKeys.indexOfFirst { it.id == keyId }
        if (index < 0) {
            return false
        }
        val planKey = savedPlanKeys[index]
        val checkedAt = System.currentTimeMillis()
        savedPlanKeys[index] = when (result) {
            is PlanUsageQueryResult.Available -> PlanUsageCachePolicy.applyAvailableUsage(
                planKey = planKey,
                usage = result.usage,
                checkedAt = checkedAt
            )
            PlanUsageQueryResult.Expired -> PlanUsageCachePolicy.applyExpired(planKey, checkedAt)
            is PlanUsageQueryResult.Failure -> PlanUsageCachePolicy.applyInvalidApiKey(planKey, checkedAt)
        }
        return true
    }

    /**
     * 在 IO 线程读取并解密本地 Key，读取完成前页面禁止编辑，避免空内存状态覆盖真实缓存。
     */
    private fun loadSavedPlanKeys() {
        viewModelScope.launch {
            val keyLoadResult = withContext(Dispatchers.IO) {
                keyStore.loadKeys()
            }
            if (keyLoadResult is PlanUsageKeyLoadResult.Loaded) {
                savedPlanKeys.addAll(keyLoadResult.keys)
            }
            publishPlanKeys { state ->
                state.copy(
                    isLoadingLocalData = false,
                    isAddKeyPanelVisible = savedPlanKeys.isEmpty(),
                    localDataWarningMessage = when (keyLoadResult) {
                        PlanUsageKeyLoadResult.Unreadable -> UNREADABLE_LOCAL_DATA_WARNING
                        else -> null
                    }
                )
            }
        }
    }

    /**
     * 复制当前内存数据并提交保存；同一时刻只运行一个加密写入任务，等待中的快照仅保留最新版本。
     */
    private fun schedulePlanKeysPersistence() {
        pendingPlanKeysSnapshot = savedPlanKeys.toList()
        if (planKeysPersistenceJob?.isActive == true) {
            return
        }
        planKeysPersistenceJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (true) {
                val planKeysSnapshot = pendingPlanKeysSnapshot ?: break
                pendingPlanKeysSnapshot = null
                // 页面立即退出时也要完成已经提交的敏感数据写入，但实际加密仍只在 IO 线程执行。
                withContext(NonCancellable + Dispatchers.IO) {
                    keyStore.saveKeys(planKeysSnapshot)
                }
            }
        }
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

    /** 将一次性副作用写入缓冲通道，页面短暂重建时由新的订阅者继续消费。 */
    private fun sendEvent(event: PlanUsageUiEvent) {
        eventChannel.trySend(event)
    }

    private companion object {
        private const val ADD_KEY_REQUEST_TRACE = "[添加 Key]"
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
    /** 本地密文完成读取前禁止页面编辑，防止初始空状态覆盖设备上已经保存的数据。 */
    val isLoadingLocalData: Boolean = false,
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
