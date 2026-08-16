package com.hss.myroutin.activity

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hss.myroutin.R
import com.hss.myroutin.databinding.ActivityRoutinWebBinding
import com.hss.myroutin.model.RoutinRecentGroup
import com.hss.myroutin.widget.MyToastD
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 说明：统一承载 Routin 站内网页，复用登录 Cookie、加载反馈和站外跳转边界。
 *
 * @作者 huangssh
 * @版本 5.1
 */
class RoutinWebActivity : AppCompatActivity() {

    /** WebView 与加载状态共用的页面绑定，Activity 销毁时一并释放网页实例。 */
    private lateinit var binding: ActivityRoutinWebBinding

    /** 第三方登录说明弹窗只保留一个实例，避免网页连续重定向造成重复弹出。 */
    private var thirdPartyLoginDialog: AlertDialog? = null

    /** 中文排版提醒只保留一个实例，并在当前安装中仅展示一次。 */
    private var languageGuideDialog: AlertDialog? = null

    /** 临时 WebView 只用于解析 window.open 的目标地址，不承载可见网页内容。 */
    private var pendingPopupWebView: WebView? = null

    /** 标记从登录页进入的站内 OAuth 中转，确保后续站外重定向仍能触发说明弹窗。 */
    private var thirdPartyLoginRedirectPending = false

    /** 主页面加载失败后阻止 onPageFinished 再次恢复加载提示，避免错误页持续显示获取中。 */
    private var mainFrameLoadFailed = false

    /** 当前入口传入的页面标题，确保签到和套餐订阅复用容器但保留各自语义。 */
    private val pageTitle by lazy {
        intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { getString(R.string.app_name) }
    }

    /** 仅接受受信任的 Routin HTTPS 地址，无效参数回退到每日签到页。 */
    private val initialPageUrl by lazy {
        intent.getStringExtra(EXTRA_URL)
            ?.takeIf { isRoutinPage(Uri.parse(it)) }
            ?: DAILY_CHECK_IN_URL
    }

    /** 普通网页入口经过登录页后保留原始目标，避免登录成功被站点默认带回首页。 */
    private var initialPageReturnPending = false

    /** 回到原始目标页后清理登录过程历史，返回键直接退出当前网页容器。 */
    private var clearHistoryAfterInitialPageReturn = false

    /** Routin 登录为 SPA 路由，短轮询用于捕获不触发页面完成回调的登录成功跳转。 */
    private val initialPageReturnHandler = Handler(Looper.getMainLooper())
    private val initialPageReturnPoll = object : Runnable {
        override fun run() {
            maybeReturnToInitialPageAfterLogin(
                binding.webRoutin,
                binding.webRoutin.url.orEmpty()
            )
        }
    }

    /** 设置页同步入口使用同一个网页容器，成功后先让用户决定是否返回设置页。 */
    private val recentGroupSyncMode by lazy {
        intent.getBooleanExtra(EXTRA_RECENT_GROUP_SYNC, false)
    }

    /** 同步模式下仅在用户登录后将页面导航回模型请求日志，避免停留在账户首页。 */
    private var recentGroupLogsRedirected = false

    /** 页面表格由前端异步渲染，使用主线程短轮询等待最近一条成功记录出现。 */
    private var recentGroupSyncAttempts = 0
    private val recentGroupSyncHandler = Handler(Looper.getMainLooper())
    private val recentGroupSyncPoll = object : Runnable {
        override fun run() {
            queryRecentGroupFromPage()
        }
    }

    /** 未登录时站点可能保留日志 URL，仅通过页面中的登录表单识别并隐藏原生加载提示。 */
    private var recentGroupWaitingForLogin = false

    /** 日志解析达到上限后停止恢复加载提示，避免页面重复完成回调造成无限等待。 */
    private var recentGroupSyncStopped = false

    /** 抓取成功后暂存结果，用户选择返回设置时再通过 Activity Result 交给设置页。 */
    private var pendingRecentGroup: RoutinRecentGroup? = null

    /** 结果弹窗只在首次抓取成功时展示，点击“继续查看”后不因页面刷新重复出现。 */
    private var recentGroupResultAcknowledged = false

    /** 最近分组结果弹窗只保留一个实例，避免异步脚本回调重复创建弹窗。 */
    private var recentGroupResultDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoutinWebBinding.inflate(layoutInflater)
        setContentView(binding.root)
        restoreInitialPageReturnState(savedInstanceState)
        restorePendingRecentGroup(savedInstanceState)
        configureActionBar()
        configureWebView()
        restoreOrLoadPage(savedInstanceState)
        renderLoadingState(
            binding.webRoutin.url ?: initialPageUrl,
            isPageLoading = savedInstanceState == null && pendingRecentGroup == null
        )
        pendingRecentGroup?.takeIf { !recentGroupResultAcknowledged }?.let { recentGroup ->
            binding.webRoutin.post { showRecentGroupResultDialog(recentGroup) }
        }
    }

    /** 配置独立网页页的标题和返回入口，避免用户被困在内嵌站点中。 */
    private fun configureActionBar() {
        supportActionBar?.apply {
            title = pageTitle
            setDisplayHomeAsUpEnabled(true)
        }
    }

    /**
     * 启用 Routin 站点所需的 JavaScript、DOM 存储和首方 Cookie，同时禁止网页访问本机文件与内容 URI。
     */
    private fun configureWebView() {
        binding.webRoutin.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            allowFileAccess = false
            allowContentAccess = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        binding.webRoutin.webChromeClient = RoutinWebChromeClient()
        binding.webRoutin.webViewClient = RoutinWebViewClient()
    }

    /**
     * 配置变更后恢复已打开网页与历史记录；首次进入加载当前入口传入的 Routin 地址。
     * @param savedInstanceState Activity 保存的 WebView 状态
     */
    private fun restoreOrLoadPage(savedInstanceState: Bundle?) {
        val restoredState = savedInstanceState?.let(binding.webRoutin::restoreState)
        if (restoredState == null) {
            binding.webRoutin.loadUrl(initialPageUrl)
        } else if (recentGroupSyncMode) {
            maybeStartRecentGroupSync(binding.webRoutin, binding.webRoutin.url.orEmpty())
        } else {
            maybeReturnToInitialPageAfterLogin(
                binding.webRoutin,
                binding.webRoutin.url.orEmpty()
            )
        }
    }

    /**
     * 区分网页本身的加载与最近分组日志解析；登录页必须保持可操作，不能被日志提示遮挡。
     * @param url 当前或即将展示的网页地址
     * @param isPageLoading WebView 主页面是否仍在加载
     */
    private fun renderLoadingState(url: String?, isPageLoading: Boolean) {
        val currentPath = url?.let(Uri::parse)?.path?.trimEnd('/')
        val waitingForLogin = recentGroupSyncMode && (
            recentGroupWaitingForLogin || isRoutinLoginPage(url)
        )
        val showRecentGroupLoading = recentGroupSyncMode &&
            pendingRecentGroup == null &&
            !recentGroupSyncStopped &&
            !waitingForLogin &&
            currentPath == RECENT_GROUP_LOGS_PATH
        binding.llRecentGroupLoading.visibility = if (showRecentGroupLoading) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.progressRoutin.visibility = if (
            isPageLoading && !showRecentGroupLoading && !waitingForLogin
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    /**
     * 优先返回网页内部历史，历史为空时才退出页面。
     */
    private fun navigateBack() {
        if (recentGroupSyncMode) {
            pendingRecentGroup?.let {
                finishRecentGroupSync(it)
                return
            }
        }
        if (binding.webRoutin.canGoBack()) {
            binding.webRoutin.goBack()
        } else {
            finish()
        }
    }

    /**
     * 将非 Routin 域名的顶层跳转交给系统处理，防止第三方页面在签到 WebView 中继续堆积历史。
     * @param uri 网页请求的目标地址
     */
    private fun openExternalPage(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { MyToastD.show(getString(R.string.routin_web_open_external_failed)) }
    }

    /**
     * 登录页发起站外跳转时阻止第三方授权并引导使用密码，其他普通外链沿用系统打开。
     * @param sourceUrl 发起跳转的当前网页地址
     * @param targetUri 即将打开的站外地址
     */
    private fun handleExternalNavigation(sourceUrl: String?, targetUri: Uri) {
        if (isRoutinLoginPage(sourceUrl) || thirdPartyLoginRedirectPending) {
            thirdPartyLoginRedirectPending = false
            showThirdPartyLoginDialog()
        } else {
            openExternalPage(targetUri)
        }
    }

    /**
     * 只在 Routin 登录路径识别第三方授权跳转，避免普通页面的站外链接被误判。
     * @param url 当前 WebView 页面地址
     * @return 是否为 Routin 登录页
     */
    private fun isRoutinLoginPage(url: String?): Boolean {
        val uri = url?.let(Uri::parse) ?: return false
        if (!isRoutinPage(uri)) return false
        return uri.path?.trimEnd('/') == LOGIN_PATH
    }

    /**
     * 仅将登录页发起的普通注册跳转替换为邀请链接，避免改写已有邀请码或其他站内页面。
     * @param sourceUrl 发起注册跳转的当前网页地址
     * @param targetUri 即将打开的注册页地址
     * @return 是否已由主 WebView 加载邀请注册链接
     */
    private fun redirectToInviteRegisterPage(sourceUrl: String?, targetUri: Uri): Boolean {
        if (!isStandardRegisterPage(targetUri)) return false
        if (!isRoutinLoginPage(sourceUrl)) {
            Log.w(REGISTER_LOG_TAG, "检测到普通注册页跳转，但来源不是登录页，未执行邀请替换")
            return false
        }
        Log.i(REGISTER_LOG_TAG, "注册跳转已静默替换：$REGISTER_PATH -> $INVITE_REGISTER_PATH")
        binding.webRoutin.loadUrl(INVITE_REGISTER_URL)
        return true
    }

    /** 判断目标是否为不带邀请码的 Routin 注册页。 */
    private fun isStandardRegisterPage(uri: Uri): Boolean {
        if (!isRoutinPage(uri)) return false
        return uri.path?.trimEnd('/') == REGISTER_PATH
    }

    /** 判断目标是否为当前 App 使用的邀请注册页。 */
    private fun isInviteRegisterPage(uri: Uri): Boolean {
        if (!isRoutinPage(uri)) return false
        return uri.path?.trimEnd('/') == INVITE_REGISTER_PATH
    }

    /**
     * 提前在 Routin 页面安装注册点击监听，覆盖套餐页通过 SPA 路由进入登录页的场景。
     * @param webView 当前显示 Routin 站内页面的 WebView
     */
    private fun installInviteRegisterClickInterceptor(webView: WebView) {
        webView.evaluateJavascript(INVITE_REGISTER_INTERCEPT_SCRIPT) { result ->
            when (result) {
                "\"installed\"" -> Log.i(REGISTER_LOG_TAG, "Routin 注册链接拦截已安装")
                "\"already_installed\"" -> Log.i(REGISTER_LOG_TAG, "Routin 注册链接拦截已存在")
                else -> Log.w(REGISTER_LOG_TAG, "Routin 注册链接拦截安装失败")
            }
        }
    }

    /**
     * 仅输出注册路径的加载阶段，不记录查询参数、账号信息或 Cookie。
     * @param url WebView 当前加载地址
     * @param phase 当前加载阶段
     */
    private fun logRegisterPageState(url: String, phase: String) {
        val uri = Uri.parse(url)
        when {
            isStandardRegisterPage(uri) -> Log.w(
                REGISTER_LOG_TAG,
                "普通注册页$phase，当前地址未带邀请码：$REGISTER_PATH"
            )
            isInviteRegisterPage(uri) -> Log.i(
                REGISTER_LOG_TAG,
                "邀请注册页$phase：$INVITE_REGISTER_PATH"
            )
        }
    }

    /** 阻止第三方授权后回到 Routin 登录页，并引导用户切换到密码登录。 */
    private fun showThirdPartyLoginDialog() {
        if (isFinishing || isDestroyed || thirdPartyLoginDialog?.isShowing == true) return
        if (!isRoutinLoginPage(binding.webRoutin.url)) {
            binding.webRoutin.loadUrl(ROUTIN_LOGIN_URL)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.third_party_login_password_title)
            .setMessage(R.string.third_party_login_password_message)
            .setPositiveButton(R.string.third_party_login_use_password, null)
            .create()
        dialog.setOnDismissListener {
            if (thirdPartyLoginDialog === dialog) thirdPartyLoginDialog = null
        }
        thirdPartyLoginDialog = dialog
        dialog.show()
    }

    /**
     * 处理 window.open 创建的首个有效地址，站内页回到主 WebView，站外页进入统一拦截流程。
     * @param sourceUrl 发起新窗口的网页地址
     * @param targetUri 新窗口准备打开的地址
     * @return 是否已消费该地址
     */
    private fun handlePopupNavigation(sourceUrl: String?, targetUri: Uri): Boolean {
        if (targetUri.toString() == ABOUT_BLANK_URL) return false
        if (redirectToInviteRegisterPage(sourceUrl, targetUri)) return true
        if (isRoutinPage(targetUri)) {
            binding.webRoutin.loadUrl(targetUri.toString())
        } else {
            handleExternalNavigation(sourceUrl, targetUri)
        }
        return true
    }

    /** 释放仅用于解析新窗口地址的临时 WebView，避免其继续持有 Activity。 */
    private fun disposePopupWebView() {
        pendingPopupWebView?.apply {
            stopLoading()
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            destroy()
        }
        pendingPopupWebView = null
    }

    /**
     * 仅允许 HTTPS 的 Routin 主域及其子域继续留在内嵌页，登录页也沿用同一会话 Cookie。
     * @param uri 待打开的网页地址
     * @return 是否应由当前 WebView 继续加载
     */
    private fun isRoutinPage(uri: Uri): Boolean {
        if (!uri.isHierarchical || !uri.scheme.equals(HTTPS_SCHEME, ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host == ROUTIN_DOMAIN || host.endsWith(".$ROUTIN_DOMAIN")
    }

    override fun onSupportNavigateUp(): Boolean {
        navigateBack()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        navigateBack()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_INITIAL_PAGE_RETURN_PENDING, initialPageReturnPending)
        outState.putBoolean(
            STATE_CLEAR_HISTORY_AFTER_INITIAL_PAGE_RETURN,
            clearHistoryAfterInitialPageReturn
        )
        pendingRecentGroup?.let { recentGroup ->
            outState.putString(STATE_PENDING_GROUP_NAME, recentGroup.groupName)
            outState.putDouble(STATE_PENDING_MULTIPLIER, recentGroup.multiplier)
            outState.putString(STATE_PENDING_REQUEST_TIME, recentGroup.requestTime)
            outState.putBoolean(STATE_RESULT_ACKNOWLEDGED, recentGroupResultAcknowledged)
        }
        binding.webRoutin.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        binding.webRoutin.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webRoutin.onResume()
    }

    override fun onDestroy() {
        initialPageReturnHandler.removeCallbacks(initialPageReturnPoll)
        recentGroupSyncHandler.removeCallbacks(recentGroupSyncPoll)
        thirdPartyLoginDialog?.dismiss()
        thirdPartyLoginDialog = null
        languageGuideDialog?.dismiss()
        languageGuideDialog = null
        recentGroupResultDialog?.dismiss()
        recentGroupResultDialog = null
        disposePopupWebView()
        binding.webRoutin.apply {
            stopLoading()
            // 当前 SDK 将两个 Client 声明为非空类型，使用空实现解除对 Activity 内部类的引用。
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            destroy()
        }
        super.onDestroy()
    }

    /** 首次打开 Routin 网页时提示切换中文，后续入口共用同一已读标记。 */
    private fun showLanguageGuideIfNeeded(url: String) {
        if (
            !isRoutinPage(Uri.parse(url)) ||
            isFinishing ||
            isDestroyed ||
            languageGuideDialog?.isShowing == true
        ) return
        val preferences = applicationContext.getSharedPreferences(
            WEB_GUIDE_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        if (preferences.getBoolean(KEY_LANGUAGE_GUIDE_SHOWN, false)) return
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.routin_web_language_guide_title)
            .setMessage(R.string.routin_web_language_guide_message)
            .setPositiveButton(R.string.routin_web_language_guide_confirm, null)
            .create()
        dialog.setOnDismissListener {
            if (languageGuideDialog === dialog) languageGuideDialog = null
        }
        languageGuideDialog = dialog
        dialog.show()
        preferences.edit().putBoolean(KEY_LANGUAGE_GUIDE_SHOWN, true).apply()
    }

    /**
     * 普通网页入口经过登录页后只在站点首页落地时回到最初目标，注册等流程不被中途打断。
     * @param view 当前承载 Routin 页面的 WebView
     * @param url 当前页面地址
     */
    private fun maybeReturnToInitialPageAfterLogin(view: WebView, url: String) {
        if (recentGroupSyncMode || isFinishing || isDestroyed) return
        val uri = Uri.parse(url)
        if (!isRoutinPage(uri)) {
            if (initialPageReturnPending) scheduleInitialPageReturnPoll()
            return
        }
        if (isRoutinLoginPage(url)) {
            initialPageReturnPending = true
            scheduleInitialPageReturnPoll()
            return
        }
        if (!initialPageReturnPending) return
        if (isInitialPage(uri)) {
            initialPageReturnPending = false
            clearHistoryAfterInitialPageReturn = true
            initialPageReturnHandler.removeCallbacks(initialPageReturnPoll)
            return
        }
        if (!isPostLoginLandingPage(uri)) {
            scheduleInitialPageReturnPoll()
            return
        }
        initialPageReturnPending = false
        clearHistoryAfterInitialPageReturn = true
        initialPageReturnHandler.removeCallbacks(initialPageReturnPoll)
        view.loadUrl(initialPageUrl)
    }

    /** 登录成功默认只会落到站点首页或账户首页，其他站内路径继续等待以保护注册流程。 */
    private fun isPostLoginLandingPage(uri: Uri): Boolean {
        if (!isRoutinPage(uri)) return false
        return when (uri.path.orEmpty().trimEnd('/')) {
            "", DASHBOARD_PATH -> true
            else -> false
        }
    }

    /** 目标页可能附带查询参数，只按受信任主机和规范化路径判断是否已经回跳成功。 */
    private fun isInitialPage(uri: Uri): Boolean {
        if (!isRoutinPage(uri)) return false
        val targetUri = Uri.parse(initialPageUrl)
        return uri.host.equals(targetUri.host, ignoreCase = true) &&
            uri.path.orEmpty().trimEnd('/') == targetUri.path.orEmpty().trimEnd('/')
    }

    /** 保持单个登录状态轮询，避免页面完成回调与定时检查重复排队。 */
    private fun scheduleInitialPageReturnPoll() {
        initialPageReturnHandler.removeCallbacks(initialPageReturnPoll)
        initialPageReturnHandler.postDelayed(
            initialPageReturnPoll,
            INITIAL_PAGE_LOGIN_POLL_DELAY_MS
        )
    }

    /** 恢复旋转等配置变更前尚未完成的登录回跳与历史清理状态。 */
    private fun restoreInitialPageReturnState(savedInstanceState: Bundle?) {
        val state = savedInstanceState ?: return
        initialPageReturnPending = state.getBoolean(STATE_INITIAL_PAGE_RETURN_PENDING, false)
        clearHistoryAfterInitialPageReturn = state.getBoolean(
            STATE_CLEAR_HISTORY_AFTER_INITIAL_PAGE_RETURN,
            false
        )
    }

    /** 登录页只等待用户操作，登录完成后再进入日志页并轮询表格中的成功记录。 */
    private fun maybeStartRecentGroupSync(view: WebView, url: String) {
        if (!recentGroupSyncMode || recentGroupSyncStopped || isFinishing || isDestroyed) return
        val uri = Uri.parse(url)
        if (!isRoutinPage(uri)) return
        if (isRoutinLoginPage(url)) {
            recentGroupWaitingForLogin = true
            recentGroupLogsRedirected = false
            renderLoadingState(url, isPageLoading = false)
            recentGroupSyncHandler.removeCallbacks(recentGroupSyncPoll)
            recentGroupSyncHandler.postDelayed(recentGroupSyncPoll, RECENT_GROUP_LOGIN_POLL_DELAY_MS)
            return
        }
        if (uri.path?.trimEnd('/') != RECENT_GROUP_LOGS_PATH) {
            if (!recentGroupLogsRedirected) {
                recentGroupLogsRedirected = true
                view.loadUrl(RECENT_GROUP_LOGS_URL)
            } else {
                recentGroupWaitingForLogin = true
                renderLoadingState(url, isPageLoading = false)
                recentGroupSyncHandler.removeCallbacks(recentGroupSyncPoll)
                recentGroupSyncHandler.postDelayed(
                    recentGroupSyncPoll,
                    RECENT_GROUP_LOGIN_POLL_DELAY_MS
                )
            }
            return
        }
        recentGroupSyncHandler.removeCallbacks(recentGroupSyncPoll)
        recentGroupSyncHandler.postDelayed(recentGroupSyncPoll, RECENT_GROUP_SYNC_POLL_DELAY_MS)
    }

    /** 在网页端只读取已渲染的日志表，令牌列取分组名、详情列取独立计费倍率。 */
    private fun queryRecentGroupFromPage() {
        if (
            !recentGroupSyncMode ||
            recentGroupSyncStopped ||
            pendingRecentGroup != null ||
            isFinishing ||
            isDestroyed
        ) return
        val currentUrl = binding.webRoutin.url.orEmpty()
        if (isRoutinLoginPage(currentUrl)) {
            recentGroupWaitingForLogin = true
            recentGroupLogsRedirected = false
            renderLoadingState(currentUrl, isPageLoading = false)
            recentGroupSyncHandler.postDelayed(
                recentGroupSyncPoll,
                RECENT_GROUP_LOGIN_POLL_DELAY_MS
            )
            return
        }
        val currentPath = Uri.parse(currentUrl).path?.trimEnd('/')
        if (currentPath != RECENT_GROUP_LOGS_PATH) {
            if (!recentGroupLogsRedirected) {
                recentGroupLogsRedirected = true
                binding.webRoutin.loadUrl(RECENT_GROUP_LOGS_URL)
            } else {
                recentGroupWaitingForLogin = true
                renderLoadingState(currentUrl, isPageLoading = false)
                recentGroupSyncHandler.postDelayed(
                    recentGroupSyncPoll,
                    RECENT_GROUP_LOGIN_POLL_DELAY_MS
                )
            }
            return
        }
        if (recentGroupSyncAttempts >= RECENT_GROUP_SYNC_MAX_ATTEMPTS) {
            stopRecentGroupSync()
            return
        }
        binding.webRoutin.evaluateJavascript(RECENT_GROUP_QUERY_SCRIPT) { rawResult ->
            if (isFinishing || isDestroyed) return@evaluateJavascript
            val pageResult = parseRecentGroupPageResult(rawResult)
            if (pageResult?.optBoolean(RECENT_GROUP_LOGIN_REQUIRED_FIELD) == true) {
                recentGroupWaitingForLogin = true
                recentGroupLogsRedirected = false
                renderLoadingState(currentUrl, isPageLoading = false)
                recentGroupSyncHandler.postDelayed(
                    recentGroupSyncPoll,
                    RECENT_GROUP_LOGIN_POLL_DELAY_MS
                )
                return@evaluateJavascript
            }
            recentGroupWaitingForLogin = false
            recentGroupSyncAttempts += 1
            renderLoadingState(currentUrl, isPageLoading = false)
            val recentGroup = pageResult?.let(::parseRecentGroupResult)
            if (recentGroup != null) {
                completeRecentGroupSync(recentGroup)
            } else if (recentGroupSyncAttempts >= RECENT_GROUP_SYNC_MAX_ATTEMPTS) {
                stopRecentGroupSync()
            } else {
                recentGroupSyncHandler.postDelayed(
                    recentGroupSyncPoll,
                    RECENT_GROUP_SYNC_POLL_DELAY_MS
                )
            }
        }
    }

    /** 将 WebView 的脚本结果解码为对象，供登录态和最近分组共用同一次解析结果。 */
    private fun parseRecentGroupPageResult(rawResult: String): JSONObject? {
        return runCatching {
            when (val value = JSONTokener(rawResult).nextValue()) {
                is JSONObject -> value
                is String -> JSONObject(value)
                else -> null
            }
        }.getOrNull()
    }

    /** 将完整的日志脚本结果转换为展示模型，字段缺失时继续等待表格渲染。 */
    private fun parseRecentGroupResult(json: JSONObject): RoutinRecentGroup? {
        val groupName = json.optString("groupName").trim()
        val requestTime = json.optString("requestTime").trim()
        val multiplier = json.optDouble("multiplier", Double.NaN)
        return if (groupName.isNotEmpty() && requestTime.isNotEmpty() && multiplier.isFinite()) {
            RoutinRecentGroup(groupName, multiplier, requestTime)
        } else {
            null
        }
    }

    /** 达到解析上限后只提示一次并停止加载状态，页面后续回调不得重新启动轮询。 */
    private fun stopRecentGroupSync() {
        if (recentGroupSyncStopped) return
        recentGroupSyncStopped = true
        recentGroupSyncHandler.removeCallbacks(recentGroupSyncPoll)
        binding.llRecentGroupLoading.visibility = View.GONE
        binding.progressRoutin.visibility = View.GONE
        MyToastD.show(getString(R.string.settings_routin_account_sync_failed))
    }

    /** 暂存不含凭证的同步摘要，网页登录会话留在 WebView Cookie 中并等待用户选择。 */
    private fun completeRecentGroupSync(recentGroup: RoutinRecentGroup) {
        if (pendingRecentGroup != null) return
        recentGroupSyncHandler.removeCallbacks(recentGroupSyncPoll)
        pendingRecentGroup = recentGroup
        recentGroupResultAcknowledged = false
        showRecentGroupResultDialog(recentGroup)
    }

    /** 展示抓取摘要并等待用户决定继续查看日志还是返回设置页。 */
    private fun showRecentGroupResultDialog(recentGroup: RoutinRecentGroup) {
        if (
            !recentGroupSyncMode ||
            recentGroupResultAcknowledged ||
            recentGroupResultDialog?.isShowing == true ||
            isFinishing ||
            isDestroyed
        ) return
        val dialog = AlertDialog.Builder(this, R.style.Theme_MyRoutin_RecentGroupAlertDialog)
            .setTitle(R.string.settings_routin_account_sync_result_title)
            .setMessage(formatRecentGroupResultMessage(recentGroup))
            .setNegativeButton(R.string.settings_routin_account_sync_result_continue) { _, _ ->
                recentGroupResultAcknowledged = true
            }
            .setPositiveButton(R.string.settings_routin_account_sync_result_return) { _, _ ->
                finishRecentGroupSync(recentGroup)
            }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            // “继续查看”是次要操作，使用主题辅助文字色，与品牌蓝的“返回设置”拉开层级。
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(
                getColor(R.color.plan_usage_text_secondary)
            )
        }
        dialog.setOnDismissListener {
            if (recentGroupResultDialog === dialog) recentGroupResultDialog = null
        }
        recentGroupResultDialog = dialog
        dialog.show()
        // 加载提示持续到结果弹窗真正显示，避免网页异步渲染期间出现无反馈空档。
        renderLoadingState(binding.webRoutin.url, isPageLoading = false)
    }

    /** 将分组、倍率和请求时间组成弹窗摘要，并沿用设置页的倍率语义色。 */
    private fun formatRecentGroupResultMessage(recentGroup: RoutinRecentGroup): CharSequence {
        val multiplierText = getString(
            R.string.settings_routin_account_multiplier,
            formatMultiplier(recentGroup.multiplier)
        )
        val message = getString(
            R.string.settings_routin_account_sync_result_message,
            recentGroup.groupName,
            multiplierText,
            formatRequestTime(recentGroup.requestTime)
        )
        val multiplierStart = message.indexOf(multiplierText)
        if (multiplierStart < 0) return message
        val multiplierColorResId = when {
            recentGroup.multiplier < STANDARD_QUOTA_MULTIPLIER -> R.color.plan_usage_success
            recentGroup.multiplier > STANDARD_QUOTA_MULTIPLIER -> R.color.plan_usage_danger
            else -> R.color.plan_usage_text_secondary
        }
        return SpannableString(message).apply {
            setSpan(
                ForegroundColorSpan(getColor(multiplierColorResId)),
                multiplierStart,
                multiplierStart + multiplierText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** 统一倍率显示，避免整数倍率在弹窗中显示成带无意义小数的文案。 */
    private fun formatMultiplier(multiplier: Double): String {
        return if (multiplier % 1.0 == 0.0) {
            multiplier.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", multiplier)
                .trimEnd('0')
                .trimEnd('.')
        }
    }

    /** 将日志页面时间压缩为弹窗摘要；无法解析时保留网页原文便于用户核对。 */
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

    /** 将暂存结果通过 Activity Result 返回设置页，并结束当前网页容器。 */
    private fun finishRecentGroupSync(recentGroup: RoutinRecentGroup) {
        recentGroupSyncHandler.removeCallbacks(recentGroupSyncPoll)
        recentGroupResultDialog?.dismiss()
        recentGroupResultDialog = null
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_SYNC_GROUP_NAME, recentGroup.groupName)
                putExtra(EXTRA_SYNC_MULTIPLIER, recentGroup.multiplier)
                putExtra(EXTRA_SYNC_REQUEST_TIME, recentGroup.requestTime)
            }
        )
        finish()
    }

    /** 恢复旋转等配置变更前已抓取但尚未返回设置页的结果。 */
    private fun restorePendingRecentGroup(savedInstanceState: Bundle?) {
        if (!recentGroupSyncMode) return
        val state = savedInstanceState ?: return
        val groupName = state.getString(STATE_PENDING_GROUP_NAME)?.trim().orEmpty()
        val requestTime = state.getString(STATE_PENDING_REQUEST_TIME)?.trim().orEmpty()
        val multiplier = state.getDouble(STATE_PENDING_MULTIPLIER, Double.NaN)
        if (groupName.isEmpty() || requestTime.isEmpty() || !multiplier.isFinite()) return
        pendingRecentGroup = RoutinRecentGroup(groupName, multiplier, requestTime)
        recentGroupResultAcknowledged = state.getBoolean(
            STATE_RESULT_ACKNOWLEDGED,
            false
        )
    }

    /** 统一管理网页加载反馈与站内、站外导航边界，不读取或解释网页业务状态。 */
    private inner class RoutinWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            mainFrameLoadFailed = false
            renderLoadingState(url, isPageLoading = true)
            logRegisterPageState(url, "开始加载")
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (mainFrameLoadFailed) {
                binding.progressRoutin.visibility = View.GONE
                binding.llRecentGroupLoading.visibility = View.GONE
            } else {
                renderLoadingState(url, isPageLoading = false)
            }
            if (isRoutinPage(Uri.parse(url))) {
                installInviteRegisterClickInterceptor(view)
            }
            logRegisterPageState(url, "加载完成")
            if (!isRoutinLoginPage(url)) thirdPartyLoginRedirectPending = false
            super.onPageFinished(view, url)
            showLanguageGuideIfNeeded(url)
            maybeReturnToInitialPageAfterLogin(view, url)
            if (clearHistoryAfterInitialPageReturn && isInitialPage(Uri.parse(url))) {
                view.clearHistory()
                clearHistoryAfterInitialPageReturn = false
            }
            maybeStartRecentGroupSync(view, url)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                mainFrameLoadFailed = true
                binding.progressRoutin.visibility = View.GONE
                binding.llRecentGroupLoading.visibility = View.GONE
                MyToastD.show(getString(R.string.routin_web_load_failed))
            }
            super.onReceivedError(view, request, error)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            if (isRoutinPage(request.url)) {
                if (redirectToInviteRegisterPage(view.url, request.url)) return true
                if (
                    isRoutinLoginPage(view.url) &&
                    !isRoutinLoginPage(request.url.toString()) &&
                    !isInviteRegisterPage(request.url)
                ) {
                    thirdPartyLoginRedirectPending = true
                }
                return false
            }
            handleExternalNavigation(view.url, request.url)
            return true
        }
    }

    /** 捕获网页通过 window.open 发起的新窗口，避免第三方登录绕过主 WebView 的导航拦截。 */
    private inner class RoutinWebChromeClient : WebChromeClient() {

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            if (!isUserGesture) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            disposePopupWebView()
            val popupWebView = WebView(this@RoutinWebActivity).apply {
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                }
                webViewClient = PopupNavigationWebViewClient(view.url)
            }
            pendingPopupWebView = popupWebView
            transport.webView = popupWebView
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            disposePopupWebView()
            super.onCloseWindow(window)
        }
    }

    /** 读取 window.open 的真实目标地址，about:blank 只作为中间页继续等待后续导航。 */
    private inner class PopupNavigationWebViewClient(
        private val sourceUrl: String?
    ) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val handled = handlePopupNavigation(sourceUrl, request.url)
            if (handled) disposePopupWebView()
            return handled
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val targetUri = Uri.parse(url)
            super.onPageStarted(view, url, favicon)
            if (handlePopupNavigation(sourceUrl, targetUri)) disposePopupWebView()
        }
    }

    companion object {
        /** 创建每日签到网页入口，签到资格与结果只由网页账户会话决定。 */
        fun createDailyCheckInIntent(context: Context): Intent {
            return createIntent(context, R.string.daily_check_in_title, DAILY_CHECK_IN_URL)
        }

        /** 创建套餐订阅网页入口，App 仅展示套餐页，不参与扫码订阅流程。 */
        fun createPlanSubscriptionIntent(context: Context): Intent {
            return createIntent(
                context,
                R.string.plan_subscription_title,
                PLAN_SUBSCRIPTION_URL
            )
        }

        /** 创建设置页使用的登录同步入口，网页成功后先弹出摘要确认再返回结果。 */
        fun createRecentGroupSyncIntent(context: Context): Intent {
            return createIntent(
                context,
                R.string.settings_routin_account_title,
                RECENT_GROUP_LOGS_URL
            ).apply {
                putExtra(EXTRA_RECENT_GROUP_SYNC, true)
            }
        }

        /** 同步成功返回的分组字段，原生侧只接收展示信息而不接收网页登录凭证。 */
        const val EXTRA_SYNC_GROUP_NAME = "routin_sync_group_name"
        const val EXTRA_SYNC_MULTIPLIER = "routin_sync_multiplier"
        const val EXTRA_SYNC_REQUEST_TIME = "routin_sync_request_time"

        /**
         * 将受信任地址和本地标题封装为统一网页 Intent。
         * @param context 发起页面的上下文
         * @param titleResId 网页容器使用的本地标题资源
         * @param url Routin 站内 HTTPS 地址
         */
        private fun createIntent(context: Context, titleResId: Int, url: String): Intent {
            return Intent(context, RoutinWebActivity::class.java).apply {
                putExtra(EXTRA_TITLE, context.getString(titleResId))
                putExtra(EXTRA_URL, url)
            }
        }

        private const val DAILY_CHECK_IN_URL = "https://routin.ai/dashboard/lottery"
        private const val PLAN_SUBSCRIPTION_URL = "https://routin.ai/plans"
        private const val RECENT_GROUP_LOGS_URL =
            "https://routin.ai/dashboard/logs/model-requests"
        private const val DASHBOARD_PATH = "/dashboard"
        private const val RECENT_GROUP_LOGS_PATH = "/dashboard/logs/model-requests"
        private const val HTTPS_SCHEME = "https"
        private const val ROUTIN_DOMAIN = "routin.ai"
        private const val LOGIN_PATH = "/login"
        private const val ROUTIN_LOGIN_URL = "https://routin.ai/login"
        private const val REGISTER_PATH = "/register"
        /** 邀请注册路径单独用于日志核对，避免输出完整网址中的潜在查询参数。 */
        private const val INVITE_REGISTER_PATH = "/register/N9GL7X"
        private const val INVITE_REGISTER_URL = "https://routin.ai/register/N9GL7X"
        /** 注册跳转日志统一标签，便于用户在 Logcat 中单独筛选。 */
        private const val REGISTER_LOG_TAG = "RoutinWeb"
        private const val ABOUT_BLANK_URL = "about:blank"
        private const val EXTRA_TITLE = "routin_web_title"
        private const val EXTRA_URL = "routin_web_url"
        private const val WEB_GUIDE_PREFERENCES_NAME = "routin_web_guide_preferences"
        private const val KEY_LANGUAGE_GUIDE_SHOWN = "language_guide_shown"
        private const val EXTRA_RECENT_GROUP_SYNC = "routin_recent_group_sync"
        private const val RECENT_GROUP_LOGIN_REQUIRED_FIELD = "loginRequired"
        private const val STATE_INITIAL_PAGE_RETURN_PENDING =
            "routin_initial_page_return_pending"
        private const val STATE_CLEAR_HISTORY_AFTER_INITIAL_PAGE_RETURN =
            "routin_clear_history_after_initial_page_return"
        private const val STATE_PENDING_GROUP_NAME = "routin_pending_group_name"
        private const val STATE_PENDING_MULTIPLIER = "routin_pending_multiplier"
        private const val STATE_PENDING_REQUEST_TIME = "routin_pending_request_time"
        private const val STATE_RESULT_ACKNOWLEDGED = "routin_result_acknowledged"
        private const val INITIAL_PAGE_LOGIN_POLL_DELAY_MS = 1_000L
        private const val RECENT_GROUP_LOGIN_POLL_DELAY_MS = 1_000L
        private const val RECENT_GROUP_SYNC_POLL_DELAY_MS = 500L
        private const val RECENT_GROUP_SYNC_MAX_ATTEMPTS = 30
        /** 1 倍为标准额度消耗基线，低于基线为节省，高于基线为额外消耗。 */
        private const val STANDARD_QUOTA_MULTIPLIER = 1.0

        /**
         * 注册链接由 SPA 路由处理，因此从任意站内入口提前安装监听器，并在点击时校验当前为登录页。
         * 监听器挂在 document 上，可覆盖登录页后续动态渲染出的注册链接。
         */
        private val INVITE_REGISTER_INTERCEPT_SCRIPT = """
            (function() {
                var interceptorFlag = '__myRoutinInviteRegisterInterceptorInstalled';
                if (window[interceptorFlag]) return 'already_installed';
                window[interceptorFlag] = true;
                document.addEventListener('click', function(event) {
                    var currentPath = window.location.pathname.replace(/\/+${'$'}/, '');
                    if (currentPath !== '$LOGIN_PATH') return;
                    var target = event.target;
                    var anchor = target && target.closest ? target.closest('a') : null;
                    if (!anchor) return;
                    var href = anchor.getAttribute('href');
                    if (!href) return;
                    var registerUrl;
                    try {
                        registerUrl = new URL(href, window.location.href);
                    } catch (error) {
                        return;
                    }
                    var host = registerUrl.hostname.toLowerCase();
                    var isRoutinHost = host === '$ROUTIN_DOMAIN' ||
                        host.endsWith('.$ROUTIN_DOMAIN');
                    var path = registerUrl.pathname.replace(/\/+${'$'}/, '');
                    if (
                        registerUrl.protocol !== '$HTTPS_SCHEME:' ||
                        !isRoutinHost ||
                        path !== '$REGISTER_PATH'
                    ) return;
                    event.preventDefault();
                    event.stopPropagation();
                    event.stopImmediatePropagation();
                    window.location.assign('$INVITE_REGISTER_URL');
                }, true);
                return 'installed';
            })();
        """.trimIndent()

        /**
         * 只读取日志表当前页第一条成功记录；页面已按请求时间倒序，令牌列即为实际分组。
         * 计费详情优先兼容“分组倍率”，并回退读取行首或分隔符后的独立“倍率”字段。
         * 不读取或返回令牌值，避免把网页登录凭证带出 WebView。
         */
        private val RECENT_GROUP_QUERY_SCRIPT = """
            (function() {
                var passwordInput = document.querySelector('input[type="password"]');
                if (passwordInput && passwordInput.getClientRects().length > 0) {
                    return { loginRequired: true };
                }
                var rows = Array.prototype.slice.call(
                    document.querySelectorAll('table tbody tr')
                );
                for (var index = 0; index < rows.length; index += 1) {
                    var cells = rows[index].querySelectorAll('td');
                    if (cells.length < 9) continue;
                    var statusCell = cells[0];
                    var statusLines = (statusCell.innerText || statusCell.textContent || '')
                        .split(/\n+/)
                        .map(function(line) { return line.trim(); })
                        .filter(Boolean);
                    // 优先使用站点语义样式，文字仅作中英文页面的兼容兜底。
                    var hasSuccessClass = statusCell.querySelector('.dashboard-success-text') !== null;
                    var hasSuccessText = /成功|success/i.test(statusLines.join(' '));
                    if (!hasSuccessClass && !hasSuccessText) continue;
                    var requestTime = statusLines.find(function(line) {
                        return /^\d{4}[/-]\d{1,2}[/-]\d{1,2}/.test(line);
                    }) || '';
                    var tokenLines = (cells[1].innerText || cells[1].textContent || '')
                        .split(/\n+/)
                        .map(function(line) { return line.trim(); })
                        .filter(Boolean);
                    var groupName = tokenLines.length > 0
                        ? tokenLines[tokenLines.length - 1].replace(/^--\s*/, '').trim()
                        : '';
                    if (!groupName || groupName === '--' || !requestTime) continue;
                    var detailText = cells[8].innerText || cells[8].textContent || '';
                    var groupMultiplierMatch = detailText.match(
                        /(?:分组倍率|Group\s+Multiplier)\s*[=:：]\s*([0-9]+(?:[.,][0-9]+)?)/i
                    ) || detailText.match(
                        /(?:^|[|\n])\s*(?:倍率|Multiplier)\s*[=:：]\s*([0-9]+(?:[.,][0-9]+)?)/i
                    );
                    var multiplier = groupMultiplierMatch
                        ? Number(groupMultiplierMatch[1].replace(',', '.'))
                        : NaN;
                    if (!isFinite(multiplier)) continue;
                    return {
                        groupName: groupName,
                        multiplier: multiplier,
                        requestTime: requestTime
                    };
                }
                return null;
            })();
        """.trimIndent()
    }
}
