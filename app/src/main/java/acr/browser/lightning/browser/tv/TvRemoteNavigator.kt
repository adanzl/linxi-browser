package acr.browser.lightning.browser.tv

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView

/**
 * Handles D-pad / remote keys for TV-style usage without a Leanback UI rewrite.
 */
class TvRemoteNavigator(
    private val drawerLayout: DrawerLayout,
    private val tabDrawer: View,
    private val bookmarkDrawer: View,
    private val toolbarViews: List<View>,
    private val contentFrame: View,
    private val tabList: RecyclerView?,
    private val bookmarkList: RecyclerView?,
    private val getWebView: () -> WebView?,
    private val openTabDrawer: () -> Unit,
    private val closeTabDrawer: () -> Unit,
    private val openBookmarkDrawer: () -> Unit,
    private val closeBookmarkDrawer: () -> Unit,
    private val focusSearch: () -> Unit
) {

    private val scrollStepPx: Int
        get() = (contentFrame.resources.displayMetrics.density * 96).toInt().coerceAtLeast(120)

    fun prepareChromeFocus() {
        toolbarViews.forEach { view ->
            view.isFocusable = true
            view.isClickable = true
        }
        contentFrame.isFocusable = true
        contentFrame.isFocusableInTouchMode = false
        contentFrame.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                getWebView()?.requestFocus()
            }
        }
        tabList?.apply {
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        bookmarkList?.apply {
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    fun requestInitialFocus() {
        contentFrame.post {
            getWebView()?.requestFocus() ?: toolbarViews.firstOrNull()?.requestFocus()
        }
    }

    /**
     * @return true if the event was consumed.
     */
    fun handleKeyEvent(event: KeyEvent, currentFocus: View?): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_MULTIPLE) {
            return false
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO -> {
                toggleTabDrawer()
                return true
            }
            KeyEvent.KEYCODE_BOOKMARK,
            KeyEvent.KEYCODE_GUIDE -> {
                toggleBookmarkDrawer()
                return true
            }
            KeyEvent.KEYCODE_SEARCH -> {
                closeDrawersIfOpen()
                focusSearch()
                return true
            }
        }

        if (isDrawerOpen()) {
            return false
        }

        if (isToolbarFocused(currentFocus)) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                getWebView()?.requestFocus() ?: contentFrame.requestFocus()
                return true
            }
            return false
        }

        val webView = getWebView() ?: return false
        val webFocused = currentFocus == null
            || currentFocus === webView
            || currentFocus === contentFrame
            || isDescendantOf(currentFocus, contentFrame)

        if (!webFocused) {
            return false
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (webView.scrollY <= 0) {
                    toolbarViews.firstOrNull()?.requestFocus()
                } else {
                    webView.scrollBy(0, -scrollStepPx)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                webView.scrollBy(0, scrollStepPx)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                webView.scrollBy(-scrollStepPx, 0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                webView.scrollBy(scrollStepPx, 0)
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                webView.pageUp(false)
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                webView.pageDown(false)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!webView.hasFocus()) {
                    webView.requestFocus()
                }
                // Let WebView attempt in-page activation when possible.
                return false
            }
        }
        return false
    }

    private fun toggleTabDrawer() {
        if (drawerLayout.isDrawerOpen(tabDrawer)) {
            closeTabDrawer()
        } else {
            closeBookmarkDrawer()
            openTabDrawer()
            tabList?.post { tabList.requestFocus() }
        }
    }

    private fun toggleBookmarkDrawer() {
        if (drawerLayout.isDrawerOpen(bookmarkDrawer)) {
            closeBookmarkDrawer()
        } else {
            closeTabDrawer()
            openBookmarkDrawer()
            bookmarkList?.post { bookmarkList.requestFocus() }
        }
    }

    private fun closeDrawersIfOpen() {
        if (drawerLayout.isDrawerOpen(tabDrawer)) {
            closeTabDrawer()
        }
        if (drawerLayout.isDrawerOpen(bookmarkDrawer)) {
            closeBookmarkDrawer()
        }
    }

    private fun isDrawerOpen(): Boolean =
        drawerLayout.isDrawerOpen(tabDrawer) || drawerLayout.isDrawerOpen(bookmarkDrawer)

    private fun isToolbarFocused(focus: View?): Boolean {
        if (focus == null) return false
        return toolbarViews.any { focus === it || isDescendantOf(focus, it) }
    }

    private fun isDescendantOf(child: View, parent: View): Boolean {
        var current: View? = child
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }
}
