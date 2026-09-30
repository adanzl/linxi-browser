@file:Suppress("DEPRECATION")

package acr.browser.lightning.browser

import acr.browser.lightning.AppTheme
import acr.browser.lightning.R
import acr.browser.lightning.ThemableBrowserActivity
import acr.browser.lightning.animation.AnimationUtils
import acr.browser.lightning.browser.bookmark.BookmarkRecyclerViewAdapter
import acr.browser.lightning.browser.color.ColorAnimator
import acr.browser.lightning.browser.di.MainHandler
import acr.browser.lightning.browser.di.injector
import acr.browser.lightning.browser.image.ImageLoader
import acr.browser.lightning.browser.keys.KeyEventAdapter
import acr.browser.lightning.browser.menu.MenuItemAdapter
import acr.browser.lightning.browser.search.IntentExtractor
import acr.browser.lightning.browser.search.SearchListener
import acr.browser.lightning.browser.search.StyleRemovingTextWatcher
import acr.browser.lightning.browser.tab.BottomDrawerTabRecyclerViewAdapter
import acr.browser.lightning.browser.tab.DesktopTabRecyclerViewAdapter
import acr.browser.lightning.browser.tab.DrawerTabRecyclerViewAdapter
import acr.browser.lightning.browser.tab.TabPager
import acr.browser.lightning.browser.tab.TabViewHolder
import acr.browser.lightning.browser.tab.TabViewState
import acr.browser.lightning.browser.theme.ThemeProvider
import acr.browser.lightning.browser.tv.TvDevice
import acr.browser.lightning.browser.tv.TvRemoteNavigator
import acr.browser.lightning.browser.ui.BookmarkConfiguration
import acr.browser.lightning.browser.ui.TabConfiguration
import acr.browser.lightning.browser.ui.UiConfiguration
import acr.browser.lightning.browser.view.ViewDelegate
import acr.browser.lightning.browser.view.delegates.BottomTabViewDelegate
import acr.browser.lightning.browser.view.delegates.DesktopTabViewDelegate
import acr.browser.lightning.browser.view.delegates.DrawerTabViewDelegate
import acr.browser.lightning.browser.view.targetUrl.LongPress
import acr.browser.lightning.constant.HTTP
import acr.browser.lightning.database.Bookmark
import acr.browser.lightning.database.HistoryEntry
import acr.browser.lightning.database.SearchSuggestion
import acr.browser.lightning.database.WebPage
import acr.browser.lightning.database.downloads.DownloadEntry
import acr.browser.lightning.databinding.BrowserActivityBottomBinding
import acr.browser.lightning.databinding.BrowserActivityDesktopBinding
import acr.browser.lightning.databinding.BrowserActivityDrawerBinding
import acr.browser.lightning.databinding.BrowserBottomTabsBinding
import acr.browser.lightning.dialog.BrowserDialog
import acr.browser.lightning.dialog.DialogItem
import acr.browser.lightning.dialog.LightningDialogBuilder
import acr.browser.lightning.dialog.LoginDialog
import acr.browser.lightning.dialog.LoginSession
import acr.browser.lightning.extensions.color
import acr.browser.lightning.extensions.drawable
import acr.browser.lightning.extensions.resizeAndShow
import acr.browser.lightning.extensions.takeIfInstance
import acr.browser.lightning.extensions.tint
import acr.browser.lightning.preference.datastore.getUnsafe
import acr.browser.lightning.search.SuggestionsAdapter
import acr.browser.lightning.ssl.createSslDrawableForState
import acr.browser.lightning.utils.value
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ImageView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.MenuRes
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import org.json.JSONTokener
import org.json.JSONObject
import javax.inject.Inject

/**
 * The base browser activity that governs the browsing experience for both default and incognito
 * browsers.
 */
abstract class BrowserActivity : ThemableBrowserActivity() {

    private lateinit var binding: ViewDelegate
    private lateinit var tabsAdapter: ListAdapter<TabViewState, TabViewHolder>
    private lateinit var bookmarksAdapter: BookmarkRecyclerViewAdapter
    private var activeRecyclerView: RecyclerView? = null

    private var menuItemShare: MenuItem? = null
    private var menuItemCopyLink: MenuItem? = null
    private var menuItemAddToHome: MenuItem? = null
    private var menuItemAddBookmark: MenuItem? = null

    private val defaultColor by lazy { color(R.color.primary_color) }
    private val backgroundDrawable by lazy { defaultColor.toDrawable() }

    private var customView: View? = null

    private var pendingScroll = -1

    private var tvRemoteNavigator: TvRemoteNavigator? = null

    @Suppress("ConvertLambdaToReference")
    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { presenter.onFileChooserResult(it) }

    @Inject
    internal lateinit var imageLoader: ImageLoader

    @Inject
    internal lateinit var keyEventAdapter: KeyEventAdapter

    @Inject
    internal lateinit var menuItemAdapter: MenuItemAdapter

    @Inject
    internal lateinit var inputMethodManager: InputMethodManager

    @Inject
    internal lateinit var presenter: BrowserPresenter

    @Inject
    internal lateinit var tabPager: TabPager

    @Inject
    internal lateinit var intentExtractor: IntentExtractor

    @Inject
    internal lateinit var lightningDialogBuilder: LightningDialogBuilder

    @Inject
    internal lateinit var uiConfiguration: UiConfiguration

    @Inject
    internal lateinit var themeProvider: ThemeProvider

    // Periodic localStorage scanner: detect user changes from web page
    private var localStorageScanScope: kotlinx.coroutines.CoroutineScope? = null
    private var localStorageScanJob: kotlinx.coroutines.Job? = null
    /** 递增以丢弃页面切换后仍返回的旧 evaluateJavascript 回调。 */
    private var localStorageScanGeneration = 0L

    @MainHandler
    @Inject
    internal lateinit var mainHandler: Handler

    /**
     * True if the activity is operating in incognito mode, false otherwise.
     */
    abstract fun isIncognito(): Boolean

    /**
     * Provide the menu used by the browser instance.
     */
    @MenuRes
    abstract fun menu(): Int

    /**
     * Provide the home icon used by the browser instance.
     */
    @DrawableRes
    abstract fun homeIcon(): Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = when (userPreferencesDataStore.tabConfiguration.getUnsafe()) {
            TabConfiguration.DESKTOP -> {
                val actualBinding = BrowserActivityDesktopBinding.inflate(LayoutInflater.from(this))
                DesktopTabViewDelegate(actualBinding)
            }

            TabConfiguration.DRAWER_SIDE -> {
                val actualBinding = BrowserActivityDrawerBinding.inflate(LayoutInflater.from(this))
                DrawerTabViewDelegate(actualBinding)
            }

            TabConfiguration.DRAWER_BOTTOM -> {
                val actualBinding = BrowserActivityBottomBinding.inflate(LayoutInflater.from(this))
                BottomTabViewDelegate(actualBinding)
            }
        }

        val bottomTabsBinding = if (binding.browserLayoutContainer != null) {
            BrowserBottomTabsBinding.inflate(layoutInflater)
        } else {
            null
        }

        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        injector.browser2ComponentBuilder()
            .activity(this)
            .browserFrame(binding.contentFrame)
            .bottomTabsLayout(bottomTabsBinding)
            .toolbarRoot(binding.uiLayout)
            .browserRoot(binding.browserLayoutContainer)
            .toolbar(binding.toolbarLayout)
            .initialIntent(intent.takeIf { savedInstanceState == null })
            .incognitoMode(isIncognito())
            .build()
            .inject(this)

        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {

            override fun onDrawerOpened(drawerView: View) {
                if (drawerView == binding.tabDrawer) {
                    presenter.onTabDrawerMoved(isOpen = true)
                } else if (drawerView == binding.bookmarkDrawer) {
                    presenter.onBookmarkDrawerMoved(isOpen = true)
                }
            }

            override fun onDrawerClosed(drawerView: View) {
                if (drawerView == binding.tabDrawer) {
                    presenter.onTabDrawerMoved(isOpen = false)
                } else if (drawerView == binding.bookmarkDrawer) {
                    presenter.onBookmarkDrawerMoved(isOpen = false)
                }
            }
        })

        binding.bookmarkDrawer.layoutParams =
            (binding.bookmarkDrawer.layoutParams as DrawerLayout.LayoutParams).apply {
                gravity = when (uiConfiguration.bookmarkConfiguration) {
                    BookmarkConfiguration.LEFT -> Gravity.START
                    BookmarkConfiguration.RIGHT -> Gravity.END
                }
            }

        binding.tabDrawer.layoutParams =
            (binding.tabDrawer.layoutParams as DrawerLayout.LayoutParams).apply {
                gravity = when (uiConfiguration.bookmarkConfiguration) {
                    BookmarkConfiguration.LEFT -> Gravity.END
                    BookmarkConfiguration.RIGHT -> Gravity.START
                }
            }

        binding.homeImageView.isVisible =
            uiConfiguration.tabConfiguration == TabConfiguration.DESKTOP || isIncognito()
        binding.homeImageView.setImageResource(homeIcon())
        binding.tabCountView.isVisible =
            uiConfiguration.tabConfiguration != TabConfiguration.DESKTOP && !isIncognito()

        if (uiConfiguration.tabConfiguration != TabConfiguration.DRAWER_SIDE) {
            binding.drawerLayout.setDrawerLockMode(
                DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
                binding.tabDrawer
            )
        }

        if (uiConfiguration.tabConfiguration != TabConfiguration.DESKTOP) {
            if (binding.browserLayoutContainer == null) {
                tabsAdapter = DrawerTabRecyclerViewAdapter(
                    onClick = presenter::onTabClick,
                    onCloseClick = presenter::onTabClose,
                    onLongClick = presenter::onTabLongClick
                )
                binding.drawerTabsList.isVisible = true
                binding.drawerTabsList.adapter = tabsAdapter
                binding.drawerTabsList.layoutManager = LinearLayoutManager(this)
                binding.drawerTabsList.itemAnimator?.takeIfInstance<SimpleItemAnimator>()
                    ?.supportsChangeAnimations = false
                binding.desktopTabsList.isVisible = false
                activeRecyclerView = binding.desktopTabsList
            } else {
                tabsAdapter = BottomDrawerTabRecyclerViewAdapter(
                    themeProvider,
                    onClick = presenter::onTabClick,
                    onLongClick = presenter::onTabLongClick,
                    onCloseClick = presenter::onTabClose,
                    onBackClick = { presenter.onBackClick() },
                    onForwardClick = { presenter.onForwardClick() },
                    onHomeClick = { presenter.onHomeClick() }
                )
                bottomTabsBinding!!.bottomTabList.adapter = tabsAdapter
                bottomTabsBinding.bottomTabList.layoutManager =
                    LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
                bottomTabsBinding.bottomTabList.itemAnimator?.takeIfInstance<SimpleItemAnimator>()
                    ?.supportsChangeAnimations = false
                binding.drawerTabsList.isVisible = false
                binding.desktopTabsList.isVisible = false
                activeRecyclerView = bottomTabsBinding.bottomTabList
            }
        } else {
            tabsAdapter = DesktopTabRecyclerViewAdapter(
                context = this,
                onClick = presenter::onTabClick,
                onCloseClick = presenter::onTabClose,
                onLongClick = presenter::onTabLongClick
            )
            binding.desktopTabsList.isVisible = true
            binding.desktopTabsList.adapter = tabsAdapter
            binding.desktopTabsList.layoutManager =
                LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            binding.desktopTabsList.itemAnimator?.takeIfInstance<SimpleItemAnimator>()
                ?.supportsChangeAnimations = false
            binding.drawerTabsList.isVisible = false
            activeRecyclerView = binding.desktopTabsList
        }

        bookmarksAdapter = BookmarkRecyclerViewAdapter(
            onClick = presenter::onBookmarkClick,
            onLongClick = presenter::onBookmarkLongClick,
            imageLoader = imageLoader
        )
        binding.bookmarkListView.adapter = bookmarksAdapter
        binding.bookmarkListView.layoutManager = LinearLayoutManager(this)

        presenter.onViewAttached(BrowserStateAdapter(this))

        val suggestionsAdapter = SuggestionsAdapter(this, isIncognito = isIncognito()).apply {
            onSuggestionInsertClick = {
                if (it is SearchSuggestion) {
                    binding.search.setText(it.title)
                    binding.search.setSelection(it.title.length)
                } else {
                    binding.search.setText(it.url)
                    binding.search.setSelection(it.url.length)
                }
            }
        }
        binding.search.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            binding.search.clearFocus()
            presenter.onSearchSuggestionClicked(suggestionsAdapter.getItem(position) as WebPage)
            inputMethodManager.hideSoftInputFromWindow(binding.root.windowToken, 0)
        }
        binding.search.setAdapter(suggestionsAdapter)
        val searchListener = SearchListener(
            onConfirm = { presenter.onSearch(binding.search.text.toString()) },
            inputMethodManager = inputMethodManager
        )
        binding.search.setOnEditorActionListener(searchListener)
        binding.search.setOnKeyListener(searchListener)
        binding.search.addTextChangedListener(StyleRemovingTextWatcher())
        binding.search.setOnFocusChangeListener { _, hasFocus ->
            presenter.onSearchFocusChanged(hasFocus)
            binding.search.selectAll()
        }

        binding.findPrevious.setOnClickListener { presenter.onFindPrevious() }
        binding.findNext.setOnClickListener { presenter.onFindNext() }
        binding.findQuit.setOnClickListener { presenter.onFindDismiss() }

        binding.homeButton.setOnClickListener { presenter.onTabCountViewClick() }
        binding.actionBack.setOnClickListener { presenter.onBackClick() }
        binding.actionForward.setOnClickListener { presenter.onForwardClick() }
        binding.actionHome.setOnClickListener { presenter.onHomeClick() }
        binding.newTabButton.setOnClickListener { presenter.onNewTabClick() }
        binding.newTabButton.setOnLongClickListener {
            presenter.onNewTabLongClick()
            true
        }
        binding.searchRefresh.setOnClickListener { presenter.onRefreshOrStopClick() }
        binding.actionAddBookmark.setOnClickListener { presenter.onStarClick() }
        binding.actionPageTools.setOnClickListener { presenter.onToolsClick() }
        binding.tabHeaderButton.setOnClickListener { presenter.onTabMenuClick() }
        binding.bookmarkBackButton.setOnClickListener { presenter.onBookmarkMenuClick() }
        binding.searchSslStatus.setOnClickListener { presenter.onSslIconClick() }

        tabPager.longPressListener = presenter::onPageLongPress

        setupTvRemoteNavigation()

        onBackPressedDispatcher.addCallback {
            presenter.onNavigateBack()
        }

        // Check for saved login session, auto-login if valid
        tryAutoLogin()
    }

    private fun setupTvRemoteNavigation() {
        val toolbarViews = listOf(
            binding.homeButton,
            binding.search,
            binding.searchRefresh,
            binding.searchSslStatus
        )
        tvRemoteNavigator = TvRemoteNavigator(
            drawerLayout = binding.drawerLayout,
            tabDrawer = binding.tabDrawer,
            bookmarkDrawer = binding.bookmarkDrawer,
            toolbarViews = toolbarViews,
            contentFrame = binding.contentFrame,
            tabList = binding.drawerTabsList.takeIf { it.isVisible },
            bookmarkList = binding.bookmarkListView,
            getWebView = { tabPager.getCurrentWebView() },
            openTabDrawer = { openTabDrawer() },
            closeTabDrawer = { closeTabDrawer() },
            openBookmarkDrawer = { openBookmarkDrawer() },
            closeBookmarkDrawer = { closeBookmarkDrawer() },
            focusSearch = {
                binding.search.requestFocus()
                binding.search.selectAll()
            }
        ).also { navigator ->
            navigator.prepareChromeFocus()
            listOf(
                binding.actionBack,
                binding.actionHome,
                binding.actionForward,
                binding.newTabButton,
                binding.tabHeaderButton,
                binding.actionAddBookmark,
                binding.actionPageTools,
                binding.bookmarkBackButton
            ).forEach { view ->
                view.isFocusable = true
                view.isClickable = true
                view.foreground = drawable(R.drawable.tv_focus_highlight)
            }
            toolbarViews.forEach { it.foreground = drawable(R.drawable.tv_focus_highlight) }
            // Remote key handling is always active; extra TV polish only when detected.
            if (TvDevice.isTelevision(this)) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                navigator.requestInitialFocus()
            }
        }
    }

    /**
     * Try to auto-login using saved session.
     * If we have a saved user with tokens, skip the login dialog entirely.
     * The localStorage scan will inject tokens into the WebView and reload.
     * If the web session is truly invalid, the scan will detect logout and
     * re-show the login dialog.
     */
    private fun tryAutoLogin() {
        android.util.Log.i("BrowserActivity", "tryAutoLogin: hasSavedUser=${LoginSession.hasSavedUser(this)}")
        if (!LoginSession.hasSavedUser(this)) {
            android.util.Log.i("BrowserActivity", "No saved user, showing login dialog")
            showLoginDialog()
            return
        }
        val username = LoginSession.getUsername(this) ?: ""
        val apiBase = LoginSession.getApiBase(this)
        if (apiBase == null) {
            showLoginDialog()
            return
        }

        // We have a saved user — trust local session and skip login dialog.
        // The localStorage scan will inject tokens into WebView if needed.
        // If the web session is truly dead, the scan detects logout and re-shows dialog.
        android.util.Log.i("BrowserActivity", "Auto-login for saved user: $username, starting localStorage scan")
        startLocalStorageScan()
    }

    private fun showLoginDialog() {
        val loginDialog = LoginDialog()
        loginDialog.setCallback(object : LoginDialog.LoginCallback {
            override fun onLoginSuccess(username: String) {
                android.util.Log.i("BrowserActivity", "User $username logged in successfully")
                LoginSession.setPendingNativeWebOverride(this@BrowserActivity, true)
                startLocalStorageScan()
                val webView = tabPager.getCurrentWebView()
                if (webView != null && LoginSession.isOurWebAppUrl(webView.url)) {
                    pushNativeSessionToWeb(
                        webView,
                        LoginSession.AtomicInjectOptions(forceOverride = true),
                    )
                }
            }
        })
        loginDialog.show(supportFragmentManager, LoginDialog.TAG)
    }

    /**
     * Start periodic scanning of WebView localStorage to detect user changes.
     * When the web app logs out or switches users, we detect it here and
     * update native configs (marks, whitelist) accordingly.
     */
    private fun startLocalStorageScan() {
        stopLocalStorageScan()
        localStorageScanGeneration++
        localStorageScanScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        localStorageScanJob = localStorageScanScope?.launch {
            while (isActive) {
                kotlinx.coroutines.delay(LOCAL_STORAGE_SCAN_INTERVAL_MS)
                checkLocalStorageUser()
            }
        }
        android.util.Log.i("BrowserActivity", "localStorage scan started")
    }

    private fun stopLocalStorageScan() {
        localStorageScanJob?.cancel()
        localStorageScanJob = null
        localStorageScanScope = null
    }

    /**
     * Evaluate JS in the current WebView to read localStorage lx_saveUser.
     * If the userId changed, update LoginSession and invalidate cached pages.
     * Only treats null as "logged out" after we've previously seen a non-null value.
     * Also injects auth tokens into localStorage if the web app doesn't have them yet.
     */
    private sealed class WebSessionReadOutcome {
        data class Present(val snapshot: LoginSession.WebSessionSnapshot) : WebSessionReadOutcome()
        data class Empty(val webLogoutSignal: Boolean) : WebSessionReadOutcome()
        object Failed : WebSessionReadOutcome()
    }

    private fun checkLocalStorageUser() {
        val webView = tabPager.getCurrentWebView() ?: return
        if (!LoginSession.isOurWebAppUrl(webView.url)) return

        if (LoginSession.isPendingNativeWebOverride(this)) {
            pushNativeSessionToWeb(
                webView,
                LoginSession.AtomicInjectOptions(forceOverride = true),
            )
            return
        }

        val scanId = ++localStorageScanGeneration
        val expectedUrl = webView.url

        webView.evaluateJavascript(LoginSession.buildReadWebSessionJs()) { result ->
            if (scanId != localStorageScanGeneration) return@evaluateJavascript
            val currentWebView = tabPager.getCurrentWebView()
            if (currentWebView !== webView || currentWebView.url != expectedUrl) return@evaluateJavascript

            when (val outcome = parseWebSessionSnapshot(result)) {
                is WebSessionReadOutcome.Failed -> {
                    android.util.Log.w("BrowserActivity", "Web session read failed, skipping sync")
                }
                is WebSessionReadOutcome.Present -> {
                    val snapshot = outcome.snapshot
                    val nativeUserId = LoginSession.getUserId(this@BrowserActivity)
                    LoginSession.resolveWebAppOrigin(expectedUrl)?.let { origin ->
                        LoginSession.markWebUserSeenOnOrigin(this@BrowserActivity, origin)
                    }
                    val webUserId = snapshot.userId
                    if (webUserId != nativeUserId) {
                        android.util.Log.i("BrowserActivity", "User changed in web: $nativeUserId -> $webUserId")
                        onWebSessionChanged(snapshot)
                    } else if (LoginSession.webTokensDifferFromNative(this@BrowserActivity, snapshot)) {
                        val nativeExpires = LoginSession.getTokenExpiresAt(this@BrowserActivity)
                        if (nativeExpires > snapshot.expiresAtMillis) {
                            android.util.Log.i(
                                "BrowserActivity",
                                "Native token fresher than web (native=$nativeExpires, web=${snapshot.expiresAtMillis}), pushing to web"
                            )
                            pushNativeSessionToWeb(
                                webView,
                                LoginSession.AtomicInjectOptions(
                                    forceOverride = false,
                                    webGuard = snapshot,
                                ),
                            )
                        } else {
                            LoginSession.syncTokensFromWeb(this@BrowserActivity, snapshot)
                            android.util.Log.i("BrowserActivity", "Synced tokens from web to native")
                        }
                    }
                }
                is WebSessionReadOutcome.Empty -> {
                    val nativeUserId = LoginSession.getUserId(this@BrowserActivity)
                    val origin = LoginSession.resolveWebAppOrigin(expectedUrl)
                    val treatAsLogout = outcome.webLogoutSignal ||
                        (origin != null && LoginSession.hasWebUserSeenOnOrigin(this@BrowserActivity, origin))
                    if (treatAsLogout && nativeUserId != null) {
                        android.util.Log.i("BrowserActivity", "User logged out in web page (origin=$origin)")
                        onWebUserLoggedOut()
                    } else if (LoginSession.hasSavedUser(this@BrowserActivity)) {
                        injectLocalStorageIfNeeded(webView)
                    }
                }
            }
        }
    }

    private fun parseWebSessionSnapshot(result: String?): WebSessionReadOutcome {
        if (result.isNullOrBlank() || result == "null") return WebSessionReadOutcome.Failed
        return try {
            val jsonStr = JSONTokener(result).nextValue() as String
            val json = JSONObject(jsonStr)
            fun opt(key: String): String? =
                json.optString(key).takeIf { it.isNotEmpty() && it != "null" }
            val snapshot = LoginSession.WebSessionSnapshot(
                userId = opt(LoginSession.LS_SAVE_USER),
                accessToken = opt(LoginSession.LS_ACCESS_TOKEN),
                refreshToken = opt(LoginSession.LS_REFRESH_TOKEN),
                expiresAtMillis = opt(LoginSession.LS_EXPIRES_AT)?.toLongOrNull() ?: 0L,
                bAuth = opt(LoginSession.LS_BAUTH) == "true",
                sessionRevision = opt(LoginSession.LS_SESSION_REVISION),
            )
            val webLogoutSignal = opt(LoginSession.LS_WEB_LOGOUT_SIGNAL) == "1"
            if (snapshot.hasLoggedInUser()) WebSessionReadOutcome.Present(snapshot)
            else WebSessionReadOutcome.Empty(webLogoutSignal)
        } catch (e: Exception) {
            android.util.Log.w("BrowserActivity", "Failed to parse web session: ${e.message}")
            WebSessionReadOutcome.Failed
        }
    }

    private fun injectLocalStorageIfNeeded(webView: android.webkit.WebView) {
        if (!LoginSession.isOurWebAppUrl(webView.url)) {
            android.util.Log.i(
                "BrowserActivity",
                "Skip token inject: url=${webView.url} is not our web app"
            )
            return
        }
        pushNativeSessionToWeb(
            webView,
            LoginSession.AtomicInjectOptions(forceOverride = false),
        )
    }

    private fun pushNativeSessionToWeb(
        webView: android.webkit.WebView,
        injectOptions: LoginSession.AtomicInjectOptions,
    ) {
        if (!LoginSession.isOurWebAppUrl(webView.url)) return
        val pageUrl = webView.url ?: return
        android.util.Log.i(
            "BrowserActivity",
            "Pushing native session into WebView (options=$injectOptions)"
        )
        LoginSession.performVerifiedNativeInject(this, webView, pageUrl, injectOptions) {
            webView.reload()
        }
    }

    /**
     * 网页切换账号或首次以网页会话为准：整组替换原生凭证并刷新缓存页。
     */
    private fun onWebSessionChanged(snapshot: LoginSession.WebSessionSnapshot) {
        LoginSession.replaceSessionFromWeb(this, snapshot)
        invalidateCachedPages()
        android.util.Log.i("BrowserActivity", "Replaced native session from web userId=${snapshot.userId}")
    }

    /**
     * Called when the web page logged out. Clear session and show login dialog.
     */
    private fun onWebUserLoggedOut() {
        stopLocalStorageScan()
        LoginSession.setPendingNativeWebOverride(this, false)
        LoginSession.clear(this)
        invalidateCachedPages()
        showLoginDialog()
    }

    /**
     * Delete cached homepage/bookmark HTML so they regenerate with the new user's marks.
     */
    private fun invalidateCachedPages() {
        val generatedDir = java.io.File(filesDir, "generated-html")
        if (generatedDir.exists()) {
            generatedDir.listFiles()?.forEach { it.delete() }
        }
        android.util.Log.i("BrowserActivity", "Cached pages invalidated")
    }

    override fun onNewIntent(intent: Intent) {
        intentExtractor.extractUrlFromIntent(intent)?.let(presenter::onNewAction)
        super.onNewIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocalStorageScan()
        presenter.onViewDetached()
    }

    override fun onPause() {
        super.onPause()
        presenter.onViewHidden()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(menu(), menu)
        menuItemShare = menu.findItem(R.id.action_share)
        menuItemCopyLink = menu.findItem(R.id.action_copy)
        menuItemAddToHome = menu.findItem(R.id.action_add_to_homescreen)
        menuItemAddBookmark = menu.findItem(R.id.action_add_bookmark)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return menuItemAdapter.adaptMenuItem(item)?.let(presenter::onMenuClick)?.let { true }
            ?: super.onOptionsItemSelected(item)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return keyEventAdapter.adaptKeyEvent(event)?.let(presenter::onKeyComboClick)?.let { true }
            ?: super.onKeyUp(keyCode, event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val navigator = tvRemoteNavigator
        if (navigator != null && navigator.handleKeyEvent(event, currentFocus)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * @see BrowserContract.View.renderState
     */
    fun renderState(viewState: PartialBrowserViewState) {
        viewState.isBackEnabled?.let { binding.actionBack.isEnabled = it }
        viewState.isForwardEnabled?.let { binding.actionForward.isEnabled = it }
        viewState.displayUrl?.let(binding.search::setText)
        viewState.sslState?.let {
            binding.searchSslStatus.setImageDrawable(createSslDrawableForState(it))
            binding.searchSslStatus.updateVisibilityForDrawable()
        }
        viewState.enableFullMenu?.let {
            menuItemShare?.isVisible = it
            menuItemCopyLink?.isVisible = it
            menuItemAddToHome?.isVisible = it
            menuItemAddBookmark?.isVisible = it
        }
        viewState.themeColor?.value()?.let(::animateColorChange)
        viewState.progress?.let {
            binding.progressView.isVisible = it != 100
            binding.progressView.progress = it
        }
        viewState.isRefresh?.let {
            binding.searchRefresh.setImageResource(
                if (it) {
                    R.drawable.ic_action_refresh
                } else {
                    R.drawable.ic_action_delete
                }
            )
        }
        viewState.bookmarks?.let(bookmarksAdapter::submitList)
        viewState.isBookmarked?.let { binding.actionAddBookmark.isSelected = it }
        viewState.isBookmarkEnabled?.let { binding.actionAddBookmark.isEnabled = it }
        viewState.isRootFolder?.let {
            binding.bookmarkBackButton.startAnimation(
                AnimationUtils.createRotationTransitionAnimation(
                    binding.bookmarkBackButton,
                    if (it) {
                        R.drawable.ic_action_star
                    } else {
                        R.drawable.ic_action_back
                    }
                )
            )
        }
        viewState.findInPage?.let {
            if (it.isEmpty()) {
                binding.findBar.isVisible = false
            } else {
                binding.findBar.isVisible = true
                binding.findQuery.text = it
            }
        }
    }

    /**
     * @see BrowserContract.View.renderTabs
     */
    fun renderTabs(tabListState: List<TabViewState>) {
        binding.tabCountView.updateCount(tabListState.size)
        val shouldScroll = tabsAdapter.itemCount < tabListState.size
        tabsAdapter.submitList(tabListState)
        val nextSelected = tabListState.indexOfFirst(TabViewState::isSelected)
        if (shouldScroll && nextSelected != -1) {
            mainHandler.post {
                if (tabPager.isBottomTabDrawerOpen()) {
                    activeRecyclerView?.smoothScrollToPosition(nextSelected)
                } else {
                    pendingScroll = nextSelected
                }
            }
        }
    }

    /**
     * @see BrowserContract.View.showAddBookmarkDialog
     */
    fun showAddBookmarkDialog(title: String, url: String, folders: List<String>) {
        lightningDialogBuilder.showAddBookmarkDialog(
            activity = this,
            currentTitle = title,
            currentUrl = url,
            folders = folders,
            onSave = presenter::onBookmarkConfirmed
        )
    }

    /**
     * @see BrowserContract.View.showBookmarkOptionsDialog
     */
    fun showBookmarkOptionsDialog(bookmark: Bookmark.Entry) {
        lightningDialogBuilder.showLongPressedDialogForBookmarkUrl(
            activity = this,
            onClick = {
                presenter.onBookmarkOptionClick(bookmark, it)
            }
        )
    }

    /**
     * @see BrowserContract.View.showEditBookmarkDialog
     */
    fun showEditBookmarkDialog(title: String, url: String, folder: String, folders: List<String>) {
        lightningDialogBuilder.showEditBookmarkDialog(
            activity = this,
            currentTitle = title,
            currentUrl = url,
            currentFolder = folder,
            folders = folders,
            onSave = presenter::onBookmarkEditConfirmed
        )
    }

    /**
     * @see BrowserContract.View.showFolderOptionsDialog
     */
    fun showFolderOptionsDialog(folder: Bookmark.Folder) {
        lightningDialogBuilder.showBookmarkFolderLongPressedDialog(
            activity = this,
            onClick = {
                presenter.onFolderOptionClick(folder, it)
            }
        )
    }

    /**
     * @see BrowserContract.View.showEditFolderDialog
     */
    fun showEditFolderDialog(oldTitle: String) {
        lightningDialogBuilder.showRenameFolderDialog(
            activity = this,
            oldTitle = oldTitle,
            onSave = presenter::onBookmarkFolderRenameConfirmed
        )
    }

    /**
     * @see BrowserContract.View.showDownloadOptionsDialog
     */
    fun showDownloadOptionsDialog(download: DownloadEntry) {
        lightningDialogBuilder.showLongPressedDialogForDownloadUrl(
            activity = this,
            onClick = {
                presenter.onDownloadOptionClick(download, it)
            }
        )
    }

    /**
     * @see BrowserContract.View.showHistoryOptionsDialog
     */
    fun showHistoryOptionsDialog(historyEntry: HistoryEntry) {
        lightningDialogBuilder.showLongPressedHistoryLinkDialog(
            activity = this,
            onClick = {
                presenter.onHistoryOptionClick(historyEntry, it)
            }
        )
    }

    /**
     * @see BrowserContract.View.showFindInPageDialog
     */
    fun showFindInPageDialog() {
        BrowserDialog.showEditText(
            this,
            R.string.action_find,
            R.string.search_hint,
            R.string.search_hint,
            presenter::onFindInPage
        )
    }

    /**
     * @see BrowserContract.View.showLinkLongPressDialog
     */
    fun showLinkLongPressDialog(longPress: LongPress) {
        BrowserDialog.show(
            this, longPress.targetUrl?.replace(HTTP, ""),
            DialogItem(title = R.string.dialog_open_new_tab) {
                presenter.onLinkLongPressEvent(
                    longPress,
                    BrowserContract.LinkLongPressEvent.NEW_TAB
                )
            },
            DialogItem(title = R.string.dialog_open_background_tab) {
                presenter.onLinkLongPressEvent(
                    longPress,
                    BrowserContract.LinkLongPressEvent.BACKGROUND_TAB
                )
            },
            DialogItem(
                title = R.string.dialog_open_incognito_tab,
                isConditionMet = !isIncognito()
            ) {
                presenter.onLinkLongPressEvent(
                    longPress,
                    BrowserContract.LinkLongPressEvent.INCOGNITO_TAB
                )
            },
            DialogItem(title = R.string.action_share) {
                presenter.onLinkLongPressEvent(longPress, BrowserContract.LinkLongPressEvent.SHARE)
            },
            DialogItem(title = R.string.dialog_copy_link) {
                presenter.onLinkLongPressEvent(
                    longPress,
                    BrowserContract.LinkLongPressEvent.COPY_LINK
                )
            })
    }

    /**
     * @see BrowserContract.View.showImageLongPressDialog
     */
    fun showImageLongPressDialog(longPress: LongPress) {
        BrowserDialog.show(
            this, longPress.targetUrl?.replace(HTTP, ""),
            DialogItem(title = R.string.dialog_open_new_tab) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.NEW_TAB
                )
            },
            DialogItem(title = R.string.dialog_open_background_tab) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.BACKGROUND_TAB
                )
            },
            DialogItem(
                title = R.string.dialog_open_incognito_tab,
                isConditionMet = !isIncognito()
            ) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.INCOGNITO_TAB
                )
            },
            DialogItem(title = R.string.action_share) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.SHARE
                )
            },
            DialogItem(title = R.string.dialog_copy_link) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.COPY_LINK
                )
            },
            DialogItem(title = R.string.dialog_download_image) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.DOWNLOAD
                )
            })
    }

    /**
     * @see BrowserContract.View.showCloseBrowserDialog
     */
    fun showCloseBrowserDialog(id: Int) {
        BrowserDialog.show(
            this, R.string.dialog_title_close_browser,
            DialogItem(title = R.string.close_tab) {
                presenter.onCloseBrowserEvent(id, BrowserContract.CloseTabEvent.CLOSE_CURRENT)
            },
            DialogItem(title = R.string.close_other_tabs) {
                presenter.onCloseBrowserEvent(id, BrowserContract.CloseTabEvent.CLOSE_OTHERS)
            },
            DialogItem(title = R.string.close_all_tabs, onClick = {
                presenter.onCloseBrowserEvent(id, BrowserContract.CloseTabEvent.CLOSE_ALL)
            })
        )
    }

    /**
     * @see BrowserContract.View.openBookmarkDrawer
     */
    fun openBookmarkDrawer() {
        binding.drawerLayout.closeDrawer(binding.tabDrawer)
        binding.drawerLayout.openDrawer(binding.bookmarkDrawer)
    }

    /**
     * @see BrowserContract.View.closeBookmarkDrawer
     */
    fun closeBookmarkDrawer() {
        binding.drawerLayout.closeDrawer(binding.bookmarkDrawer)
    }

    /**
     * @see BrowserContract.View.openTabDrawer
     */
    fun openTabDrawer() {
        binding.drawerLayout.closeDrawer(binding.bookmarkDrawer)
        if (binding.browserLayoutContainer == null) {
            binding.drawerLayout.openDrawer(binding.tabDrawer)
        } else {
            presenter.onTabDrawerMoved(isOpen = true)
            tabPager.openBottomTabDrawer()
            if (pendingScroll != -1) {
                activeRecyclerView?.scrollToPosition(pendingScroll)
                pendingScroll = -1
            }
        }
    }

    /**
     * @see BrowserContract.View.closeTabDrawer
     */
    fun closeTabDrawer() {
        if (binding.browserLayoutContainer == null) {
            binding.drawerLayout.closeDrawer(binding.tabDrawer)
        } else {
            presenter.onTabDrawerMoved(isOpen = false)
            tabPager.closeBottomTabDrawer()
        }
    }

    /**
     * @see BrowserContract.View.showToolbar
     */
    fun showToolbar() {
        tabPager.showToolbar()
    }

    /**
     * @see BrowserContract.View.showToolsDialog
     */
    fun showToolsDialog(areAdsAllowed: Boolean, shouldShowAdBlockOption: Boolean) {
        val whitelistString = if (areAdsAllowed) {
            R.string.dialog_adblock_enable_for_site
        } else {
            R.string.dialog_adblock_disable_for_site
        }

        BrowserDialog.showWithIcons(
            this, getString(R.string.dialog_tools_title),
            DialogItem(
                icon = drawable(R.drawable.ic_action_desktop),
                title = R.string.dialog_toggle_desktop,
                onClick = presenter::onToggleDesktopAgent
            ),
            DialogItem(
                icon = drawable(R.drawable.ic_block),
                colorTint = color(R.color.error_red).takeIf { areAdsAllowed },
                title = whitelistString,
                isConditionMet = shouldShowAdBlockOption,
                onClick = presenter::onToggleAdBlocking
            )
        )
    }

    /**
     * @see BrowserContract.View.showLocalFileBlockedDialog
     */
    fun showLocalFileBlockedDialog() {
        AlertDialog.Builder(this)
            .setCancelable(true)
            .setTitle(R.string.title_warning)
            .setMessage(R.string.message_blocked_local)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                presenter.onConfirmOpenLocalFile(allow = false)
            }
            .setPositiveButton(R.string.action_open) { _, _ ->
                presenter.onConfirmOpenLocalFile(allow = true)
            }
            .setOnCancelListener { presenter.onConfirmOpenLocalFile(allow = false) }
            .resizeAndShow()
    }

    /**
     * @see BrowserContract.View.showFileChooser
     */
    fun showFileChooser(intent: Intent) {
        launcher.launch(intent)
    }

    /**
     * @see BrowserContract.View.showCustomView
     */
    fun showCustomView(view: View) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        binding.root.addView(view)
        customView = view
        setFullscreen(enabled = true, immersive = true)
    }

    /**
     * @see BrowserContract.View.hideCustomView
     */
    fun hideCustomView() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        customView?.let(binding.root::removeView)
        customView = null
        setFullscreen(enabled = false, immersive = false)
    }

    /**
     * @see BrowserContract.View.clearSearchFocus
     */
    fun clearSearchFocus() {
        binding.search.clearFocus()
    }

    // TODO: update to use non deprecated flags
    private fun setFullscreen(enabled: Boolean, immersive: Boolean) {
        val window = window
        val decor = window.decorView
        if (enabled) {
            if (immersive) {
                decor.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            } else {
                decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun animateColorChange(color: Int) {
        if (!userPreferencesDataStore.colorModeEnabled.getUnsafe() || userPreferencesDataStore.useTheme.getUnsafe() != AppTheme.LIGHT || isIncognito()) {
            return
        }
        val adapter = tabsAdapter as? DesktopTabRecyclerViewAdapter
        val colorAnimator = ColorAnimator(defaultColor)
        binding.toolbar.startAnimation(
            colorAnimator.animateTo(
                color
            ) { mainColor, secondaryColor ->
                if (userPreferencesDataStore.tabConfiguration.getUnsafe() != TabConfiguration.DESKTOP) {
                    backgroundDrawable.color = mainColor
                    window.setBackgroundDrawable(backgroundDrawable)
                } else {
                    adapter?.updateForegroundTabColor(mainColor)
                }
                binding.toolbar.setBackgroundColor(mainColor)
                binding.searchContainer.background?.tint(secondaryColor)
            })
    }

    private fun ImageView.updateVisibilityForDrawable() {
        visibility = if (drawable == null) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    companion object {
        private const val LOCAL_STORAGE_SCAN_INTERVAL_MS = 3000L
    }
}
