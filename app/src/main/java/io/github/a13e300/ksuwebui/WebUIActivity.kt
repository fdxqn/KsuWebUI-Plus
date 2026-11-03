package io.github.a13e300.ksuwebui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.webkit.WebViewAssetLoader
import com.topjohnwu.superuser.nio.FileSystemManager
import io.github.a13e300.ksuwebui.data.ModuleRepository
import io.github.a13e300.ksuwebui.data.ModuleResolveResult
import io.github.a13e300.ksuwebui.data.ModuleWebCache
import io.github.a13e300.ksuwebui.databinding.ActivityWebuiBinding
import io.github.a13e300.ksuwebui.databinding.ViewWebuiErrorBinding
import io.github.a13e300.ksuwebui.shortcut.ModuleIdValidator
import io.github.a13e300.ksuwebui.shortcut.ModuleLaunchContract
import io.github.a13e300.ksuwebui.shortcut.ModuleShortcutController
import io.github.a13e300.ksuwebui.shortcut.ShortcutConfig
import io.github.a13e300.ksuwebui.shortcut.ShortcutIconRenderer
import io.github.a13e300.ksuwebui.shortcut.ShortcutStore
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
class WebUIActivity : ComponentActivity(), FileSystemService.Listener {
    private lateinit var binding: ActivityWebuiBinding
    private lateinit var webviewInterface: WebViewInterface
    private val store by lazy { ShortcutStore.get(this) }
    private val controller by lazy { ModuleShortcutController(this) }
    private val renderer = ShortcutIconRenderer()

    private var webView: WebView? = null
    private var moduleId: String? = null
    private var fromShortcut = false
    private var moduleRoot: String? = null
    private var webRoot: String? = null
    private var initialized = false
    private var errorShown = false
    private var cacheLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)

        binding = ActivityWebuiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val fallbackTop = statusBarHeight()
        binding.root.updatePadding(top = fallbackTop)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = bars.left,
                top = maxOf(bars.top, fallbackTop),
                right = bars.right,
                bottom = maxOf(bars.bottom, ime.bottom)
            )
            insets
        }

        moduleId = intent.getStringExtra(ModuleLaunchContract.EXTRA_MODULE_ID)
        fromShortcut = intent.getBooleanExtra(ModuleLaunchContract.EXTRA_FROM_SHORTCUT, false)
        val id = moduleId
        if (!ModuleIdValidator.isValid(id)) {
            showError(
                R.string.invalid_launch_request,
                R.string.invalid_launch_message,
                removeShortcut = false,
                showOpenApp = false
            )
            return
        }

        val safeId = requireNotNull(id)
        binding.skeletonTitle.text = safeId
        binding.skeletonIcon.setImageBitmap(renderer.monogram(safeId, safeId, dp(72)))

        val cached = ModuleWebCache.webroot(this, safeId)
        if (cached != null) {
            cacheLoaded = true
            moduleRoot = "${ModuleRepository.MODULES_ROOT}/$safeId"
            setupCachedWebview(cached)
        }

        onBackPressedDispatcher.addCallback(this) {
            val view = webView
            if (view != null && view.canGoBack()) view.goBack() else finish()
        }

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        WebView.setWebContentsDebuggingEnabled(
            BuildConfig.DEBUG && prefs.getBoolean("enable_web_debugging", true)
        )
        loadShortcutMetadata()
        FileSystemService.start(this)
    }

    private fun loadShortcutMetadata() {
        if (!fromShortcut) return
        val id = moduleId ?: return
        App.executor.execute {
            val config = store.get(ShortcutConfig.idFor(id))
            val label = config?.label ?: id
            val icon = config?.let { store.loadIcon(it) }
                ?: renderer.monogram(label, id, dp(72))
            postUi {
                @Suppress("DEPRECATION")
                setTaskDescription(ActivityManager.TaskDescription(label, icon))
                binding.skeletonTitle.text = label
                binding.skeletonIcon.setImageBitmap(icon)
            }
        }
    }

    override fun onServiceAvailable(fs: FileSystemManager) {
        if (initialized || errorShown) return
        val id = moduleId ?: return

        App.executor.execute {
            val result = try {
                ModuleRepository(fs).resolve(id, allowDisabled = !fromShortcut)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve module $id", e)
                null
            }
            postUi {
                if (initialized || errorShown) return@postUi
                if (result == null) {
                    ModuleWebCache.delete(this, id)
                    showError(
                        R.string.module_not_found,
                        R.string.module_not_found_message,
                        removeShortcut = fromShortcut,
                        showOpenApp = true
                    )
                    return@postUi
                }
                when (result) {
                    is ModuleResolveResult.Success -> {
                        initialized = true
                        moduleRoot = "${ModuleRepository.MODULES_ROOT}/$id"
                        webRoot = result.webRoot
                        if (!fromShortcut) binding.skeletonTitle.text = result.module.name
                        if (cacheLoaded) {
                            App.executor.execute {
                                ModuleWebCache.copy(this@WebUIActivity, fs, id)
                            }
                        } else {
                            setupWebview(fs)
                            App.executor.execute {
                                ModuleWebCache.copy(this@WebUIActivity, fs, id)
                            }
                        }
                    }

                    ModuleResolveResult.InvalidId -> {
                        ModuleWebCache.delete(this, id)
                        showError(
                            R.string.invalid_launch_request,
                            R.string.invalid_launch_message,
                            removeShortcut = false,
                            showOpenApp = false
                        )
                    }

                    ModuleResolveResult.NotFound -> {
                        ModuleWebCache.delete(this, id)
                        showError(
                            R.string.module_not_found,
                            R.string.module_not_found_message,
                            removeShortcut = fromShortcut,
                            showOpenApp = true
                        )
                    }

                    ModuleResolveResult.Disabled -> {
                        ModuleWebCache.delete(this, id)
                        showError(
                            R.string.module_disabled,
                            R.string.module_disabled_message,
                            removeShortcut = fromShortcut,
                            showOpenApp = true
                        )
                    }

                    ModuleResolveResult.NoWebUi -> {
                        ModuleWebCache.delete(this, id)
                        showError(
                            R.string.module_no_webui,
                            R.string.module_no_webui_message,
                            removeShortcut = fromShortcut,
                            showOpenApp = true
                        )
                    }
                }
            }
        }
    }

    private fun setupWebview(fs: FileSystemManager) {
        val webRootPath = webRoot ?: return
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(WEB_UI_HOST)
            .addPathHandler("/", RemoteFsPathHandler(this, File(webRootPath), fs))
            .build()
        configureWebview(assetLoader)
    }

    private fun setupCachedWebview(cache: File) {
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(WEB_UI_HOST)
            .addPathHandler("/", WebViewAssetLoader.InternalStoragePathHandler(this, cache))
            .build()
        configureWebview(assetLoader)
    }

    private fun configureWebview(assetLoader: WebViewAssetLoader) {
        val root = moduleRoot ?: return
        val client = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.scheme == "https" && url.host == WEB_UI_HOST) return false
                if (url.scheme == "about" && url.toString() == "about:blank") return false
                if (url.scheme == "http" || url.scheme == "https") {
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, url)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: ActivityNotFoundException) {
                        Log.w(TAG, "No handler for $url")
                    }
                }
                return true
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                showContent()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                showContent()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (errorShown || request?.isForMainFrame != true) return
                showError(
                    R.string.webui_load_failed,
                    R.string.webui_load_failed_message,
                    removeShortcut = false,
                    showOpenApp = true
                )
            }
        }

        binding.webview.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webviewInterface = WebViewInterface(this@WebUIActivity, this, root)
            // Kept for P0: the synchronous exec(cmd): String API cannot be preserved by
            // WebMessageListener. The origin/iframe hardening is tracked for P2.
            addJavascriptInterface(webviewInterface, "ksu")
            setWebViewClient(client)
            loadUrl("https://$WEB_UI_HOST/index.html")
        }
        webView = binding.webview
    }

    private fun showContent() {
        if (errorShown) return
        if (binding.skeleton.isVisible) {
            binding.skeleton.isVisible = false
            binding.webview.visibility = View.VISIBLE
            binding.webview.alpha = 0f
            binding.webview.animate().alpha(1f).setDuration(120).start()
        }
        applyPageBackground()
    }

    private fun applyPageBackground() {
        val view = webView ?: return
        val sampled = sampleTopColor()
        if (sampled != null) {
            setPageBackgroundColor(sampled)
            return
        }
        view.evaluateJavascript(
            "(function(){function c(e){return e?getComputedStyle(e).backgroundColor:''}" +
                "var b=c(document.body);" +
                "if(!b||b==='rgba(0, 0, 0, 0)'||b==='transparent')b=c(document.documentElement);" +
                "return b||'';})()"
        ) { raw ->
            parseCssColor(raw)?.let(::setPageBackgroundColor)
        }
    }

    private fun parseCssColor(raw: String?): Int? {
        if (raw == null) return null
        val value = raw.trim().trim('"')
        if (value.isEmpty() || value == "transparent" || value == "rgba(0, 0, 0, 0)") return null
        val match = Regex("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)").find(value)
        if (match != null) {
            return Color.rgb(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt()
            )
        }
        return runCatching { Color.parseColor(value) }.getOrNull()
    }

    private fun sampleTopColor(): Int? {
        val view = webView ?: return null
        if (view.width <= 0 || view.height <= 0 || view.alpha <= 0f) return null
        val bitmap = Bitmap.createBitmap(view.width, 1, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        val color = bitmap.getPixel(view.width / 2, 0)
        bitmap.recycle()
        return color.takeIf { Color.alpha(it) > 0 }
    }

    private fun setPageBackgroundColor(color: Int) {
        binding.root.setBackgroundColor(color)
        val light = Color.luminance(color) > 0.5f
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun showError(
        @StringRes titleRes: Int,
        @StringRes messageRes: Int,
        removeShortcut: Boolean,
        showOpenApp: Boolean
    ) {
        if (errorShown) return
        errorShown = true
        webView?.stopLoading()

        val errorBinding = ViewWebuiErrorBinding.inflate(layoutInflater)
        setContentView(errorBinding.root)
        errorBinding.errorTitle.setText(titleRes)
        errorBinding.errorMessage.setText(messageRes)
        errorBinding.errorPrimary.apply {
            when {
                removeShortcut -> {
                    setText(R.string.action_remove_shortcut)
                    setOnClickListener { removeShortcutAndFinish() }
                }

                showOpenApp -> {
                    setText(R.string.open_app)
                    setOnClickListener { openMain() }
                }

                else -> {
                    setText(R.string.close)
                    setOnClickListener { finish() }
                }
            }
        }
        errorBinding.errorSecondary.apply {
            when {
                removeShortcut -> {
                    visibility = View.VISIBLE
                    setText(R.string.open_app)
                    setOnClickListener { openMain() }
                }

                showOpenApp -> {
                    visibility = View.VISIBLE
                    setText(R.string.close)
                    setOnClickListener { finish() }
                }

                else -> visibility = View.GONE
            }
        }
    }

    private fun removeShortcutAndFinish() {
        val id = moduleId
        if (id != null) {
            val shortcutId = ShortcutConfig.idFor(id)
            controller.disable(shortcutId, getString(R.string.shortcut_removed))
            App.executor.execute {
                store.get(shortcutId)?.let { store.upsert(it.copy(pinned = false)) }
            }
        }
        finish()
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun postUi(block: () -> Unit) {
        App.handler.post {
            if (!isFinishing && !isDestroyed) block()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    override fun onLaunchFailed() {
        showError(
            R.string.please_grant_root,
            R.string.root_denied_message,
            removeShortcut = false,
            showOpenApp = true
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
        FileSystemService.removeListener(this)
    }

    companion object {
        private const val TAG = "WebUIActivity"
        const val WEB_UI_HOST = "mui.kernelsu.org"
    }
}
