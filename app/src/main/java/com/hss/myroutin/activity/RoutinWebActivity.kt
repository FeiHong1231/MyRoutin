package com.hss.myroutin.activity

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
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
import com.hss.myroutin.widget.MyToastD
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

    /** 临时 WebView 只用于解析 window.open 的目标地址，不承载可见网页内容。 */
    private var pendingPopupWebView: WebView? = null

    /** 标记从登录页进入的站内 OAuth 中转，确保后续站外重定向仍能触发说明弹窗。 */
    private var thirdPartyLoginRedirectPending = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoutinWebBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureActionBar()
        configureWebView()
        restoreOrLoadPage(savedInstanceState)
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
        }
    }

    /**
     * 优先返回网页内部历史，历史为空时才退出页面。
     */
    private fun navigateBack() {
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
        thirdPartyLoginDialog?.dismiss()
        thirdPartyLoginDialog = null
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

    /** 统一管理网页加载反馈与站内、站外导航边界，不读取或解释网页业务状态。 */
    private inner class RoutinWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            binding.progressRoutin.visibility = View.VISIBLE
            logRegisterPageState(url, "开始加载")
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView, url: String) {
            binding.progressRoutin.visibility = View.GONE
            if (isRoutinPage(Uri.parse(url))) {
                installInviteRegisterClickInterceptor(view)
            }
            logRegisterPageState(url, "加载完成")
            if (!isRoutinLoginPage(url)) thirdPartyLoginRedirectPending = false
            super.onPageFinished(view, url)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                binding.progressRoutin.visibility = View.GONE
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
    }
}
