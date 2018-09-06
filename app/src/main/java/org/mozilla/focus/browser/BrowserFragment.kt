/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.browser

import android.os.Bundle
import android.support.annotation.UiThread
import android.view.ContextMenu
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import kotlinx.android.synthetic.main.browser_overlay.*
import kotlinx.android.synthetic.main.browser_overlay.view.*
import kotlinx.android.synthetic.main.fragment_browser.*
import kotlinx.android.synthetic.main.fragment_browser.view.*
import kotlinx.coroutines.experimental.CancellationException
import mozilla.components.browser.session.Session
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.session.SessionFeature
import org.mozilla.focus.MainActivity
import org.mozilla.focus.MediaSessionHolder
import org.mozilla.focus.R
import org.mozilla.focus.ScreenController
import org.mozilla.focus.browser.cursor.CursorController
import org.mozilla.focus.ext.components
import org.mozilla.focus.ext.focusedDOMElement
import org.mozilla.focus.ext.isVisible
import org.mozilla.focus.ext.isYoutubeTV
import org.mozilla.focus.ext.requireComponents
import org.mozilla.focus.ext.takeScreenshot
import org.mozilla.focus.ext.toUri
import org.mozilla.focus.home.BundledTilesManager
import org.mozilla.focus.home.CustomTilesManager
import org.mozilla.focus.home.HomeTilesManager
import org.mozilla.focus.engine.EngineViewLifecycleFragment
import org.mozilla.focus.session.NullSession
import org.mozilla.focus.telemetry.MenuInteractionMonitor
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.telemetry.UrlTextInputLocation
import org.mozilla.focus.utils.ViewUtils.showCenteredTopToast
import org.mozilla.focus.widget.InlineAutocompleteEditText

private const val ARGUMENT_SESSION_UUID = "sessionUUID"

private const val TOAST_Y_OFFSET = 200

/**
 * Fragment for displaying the browser UI.
 */
class BrowserFragment : EngineViewLifecycleFragment(), Session.Observer {
    companion object {
        const val FRAGMENT_TAG = "browser"
        const val APP_URL_PREFIX = "firefox:"
        const val APP_URL_HOME = "${APP_URL_PREFIX}home"

        @JvmStatic
        fun createForSession(session: Session) = BrowserFragment().apply {
            arguments = Bundle().apply { putString(ARGUMENT_SESSION_UUID, session.id) }
        }
    }

    lateinit var session: Session

    private val mediaSessionHolder get() = activity as MediaSessionHolder? // null when not attached.

    val isUrlEqualToHomepage: Boolean get() = session.url == APP_URL_HOME

    /**
     * Encapsulates the cursor's components. If this value is null, the Cursor is not attached
     * to the view hierarchy.
     */
    var cursor: CursorController? = null
        @UiThread get set // Set from the UI thread so serial access is required for simplicity.

    // Cache the overlay visibility state to persist in fragment back stack
    private var overlayVisibleCached: Int? = null

    var sessionFeature: SessionFeature? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSession()
    }

    private fun initSession() {
        val sessionUUID = arguments?.getString(ARGUMENT_SESSION_UUID)
                ?: throw IllegalAccessError("No session exists")
        session = context!!.components.sessionManager.findSessionById(sessionUUID) ?: NullSession.create()
        session.register(observer = this, owner = this)
    }

    override fun onUrlChanged(session: Session, url: String) {
        if (url == APP_URL_HOME) {
            browserOverlay?.visibility = View.VISIBLE
        }
    }

    override fun onLoadingStateChanged(session: Session, loading: Boolean) {
        if (browserOverlay.isVisible) {
            browserOverlay.updateOverlayForCurrentState()
        }
    }

    private val onNavigationEvent = { event: NavigationEvent, value: String?,
            autocompleteResult: InlineAutocompleteEditText.AutocompleteResult? ->
        when (event) {
            NavigationEvent.BACK -> if (session.canGoBack) requireComponents.sessionUseCases.goBack.invoke()
            NavigationEvent.FORWARD -> if (session.canGoForward) requireComponents.sessionUseCases.goForward.invoke()
            NavigationEvent.TURBO, NavigationEvent.RELOAD -> requireComponents.sessionUseCases.reload.invoke()
            NavigationEvent.SETTINGS -> ScreenController.showSettingsScreen(fragmentManager!!)
            NavigationEvent.LOAD_URL -> {
                (activity as MainActivity).onTextInputUrlEntered(value!!, autocompleteResult!!, UrlTextInputLocation.MENU)
                setOverlayVisible(false)
            }
            NavigationEvent.LOAD_TILE -> {
                (activity as MainActivity).onNonTextInputUrlEntered(value!!)
                setOverlayVisible(false)
            }
            NavigationEvent.POCKET -> ScreenController.showPocketScreen(fragmentManager!!)
            NavigationEvent.PIN_ACTION -> {
                this@BrowserFragment.session.url.let { url ->
                    when (value) {
                        NavigationEvent.VAL_CHECKED -> {
                            CustomTilesManager.getInstance(context!!).pinSite(context!!, url,
                                    webView?.takeScreenshot())
                            browserOverlay.refreshTilesForInsertion()
                            showCenteredTopToast(context, R.string.notification_pinned_site, 0, TOAST_Y_OFFSET)
                        }
                        NavigationEvent.VAL_UNCHECKED -> {
                            url.toUri()?.let {
                                val tileId = BundledTilesManager.getInstance(context!!).unpinSite(context!!, it)
                                        ?: CustomTilesManager.getInstance(context!!).unpinSite(context!!, url)
                                // tileId should never be null, unless, for some reason we don't
                                // have a reference to the tile/the tile isn't a Bundled or Custom tile
                                if (tileId != null && !tileId.isEmpty()) {
                                    browserOverlay.removePinnedSiteFromTiles(tileId)
                                    showCenteredTopToast(context, R.string.notification_unpinned_site, 0, TOAST_Y_OFFSET)
                                }
                            }
                        }
                        else -> throw IllegalArgumentException("Unexpected value for PIN_ACTION: " + value)
                    }
                }
            }
        }
        Unit
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val layout = inflater.inflate(R.layout.fragment_browser, container, false)

        cursor = CursorController(this, cursorParent = layout.browserFragmentRoot,
                view = layout.cursorView)
        lifecycle.addObserver(cursor!!)

        with(layout.browserOverlay) {
            onNavigationEvent = this@BrowserFragment.onNavigationEvent
            navigationStateProvider = NavigationStateProvider()
            visibility = overlayVisibleCached ?: View.GONE
            onPreSetVisibilityListener = { isVisible ->
                // The overlay can clear the DOM and a previous focused element cache (e.g. reload)
                // so we need to do our own caching: see FocusedDOMElementCache for details.
                if (!isVisible) { webView?.focusedDOMElement?.cache() }
            }

            openHomeTileContextMenu = {
                activity?.openContextMenu(browserOverlay.tileContainer)
            }
            registerForContextMenu(browserOverlay.tileContainer)
        }

        layout.progressBar.initialize(this)

        // We break encapsulation here: we should use the super.webView reference but it's not init until
        // onViewCreated. However, overriding both onCreateView and onViewCreated in a single class
        // is confusing so I'd rather break encapsulation than confuse devs.
        mediaSessionHolder?.videoVoiceCommandMediaSession?.onCreateWebView(layout.webview, session)

        return layout
    }

    override fun onWebViewCreated(webView: EngineView) {
        // The SessionFeature implementation will take care of making sure that we always render the currently selected
        // session in our engine view.
        sessionFeature = SessionFeature(
            requireComponents.sessionManager,
            requireComponents.sessionUseCases,
            webView)

        if (session.url == APP_URL_HOME) {
            browserOverlay?.visibility = View.VISIBLE
        }
    }

    override fun onStart() {
        super.onStart()

        sessionFeature?.start()
    }

    override fun onStop() {
        super.onStop()

        sessionFeature?.stop()
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.remove -> {
                val homeTileAdapter = tileContainer.adapter as HomeTileAdapter
                val tileToRemove = homeTileAdapter.getItemAtPosition(browserOverlay.getFocusedTilePosition())
                        ?: return false

                // This assumes that since we're deleting from a Home Tile object that we created
                // that the Uri is valid, so we do not do error handling here.
                HomeTilesManager.removeHomeTile(tileToRemove, context!!)
                homeTileAdapter.removeItemAtPosition(browserOverlay.getFocusedTilePosition())
                TelemetryWrapper.homeTileRemovedEvent(tileToRemove)
                return true
            }
            else -> return false
        }
    }

    override fun onDestroyView() {
        mediaSessionHolder?.videoVoiceCommandMediaSession?.onDestroyWebView(webView!!, session)

        super.onDestroyView()

        lifecycle.removeObserver(cursor!!)
        cursor = null
        overlayVisibleCached = browserOverlay.visibility
        // Since we start the async jobs in View.init and Android is inflating the view for us,
        // there's no good way to pass in the uiLifecycleJob. We could consider other solutions
        // but it'll add complexity that I don't think is probably worth it.
        browserOverlay.uiLifecycleCancelJob.cancel(CancellationException("Parent lifecycle has ended"))

        sessionFeature = null
    }

    override fun onCreateContextMenu(menu: ContextMenu?, v: View?, menuInfo: ContextMenu.ContextMenuInfo?) {
        activity?.menuInflater?.inflate(R.menu.menu_context_hometile, menu)
    }

    fun onBackPressed(): Boolean {
        when {
            browserOverlay.isVisible && !isUrlEqualToHomepage -> {
                setOverlayVisible(false)
                TelemetryWrapper.userShowsHidesDrawerEvent(false)
            }
            session.canGoBack -> {
                requireComponents.sessionUseCases.goBack.invoke()
                TelemetryWrapper.browserBackControllerEvent()
            }
            else -> {
                context!!.components.sessionManager.remove()
                // Delete session, but we allow the parent to handle back behavior.
                return false
            }
        }
        return true
    }

    fun loadUrl(url: String) {
        if (url.isNotEmpty()) {
            requireComponents.sessionUseCases.loadUrl.invoke(url)
        }
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        /**
         * Key handling order:
         * - Menu to control overlay
         * - Youtube remap of BACK to ESC
         * - Cursor
         * - Return false, as unhandled
         */
        return handleSpecialKeyEvent(event) ||
                (cursor?.keyDispatcher?.dispatchKeyEvent(event) ?: false)
    }

    private fun handleSpecialKeyEvent(event: KeyEvent): Boolean {
        val actionIsDown = event.action == KeyEvent.ACTION_DOWN

        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && actionIsDown) MenuInteractionMonitor.selectPressed()

        if (event.keyCode == KeyEvent.KEYCODE_MENU && !isUrlEqualToHomepage) {
            if (actionIsDown) {
                val toShow = !browserOverlay.isVisible
                setOverlayVisible(toShow)
                TelemetryWrapper.userShowsHidesDrawerEvent(toShow)
            }
            return true
        }

        if (!browserOverlay.isVisible && session.isYoutubeTV &&
                event.keyCode == KeyEvent.KEYCODE_BACK) {
            val escKeyEvent = KeyEvent(event.action, KeyEvent.KEYCODE_ESCAPE)
            activity?.dispatchKeyEvent(escKeyEvent)
            return true
        }
        return false
    }

    /**
     * Changes the overlay visibility: this should be called instead of changing
     * [BrowserNavigationOverlay.isVisible] directly.
     */
    private fun setOverlayVisible(toShow: Boolean) {
        browserOverlay.visibility = if (toShow) View.VISIBLE else View.GONE
        if (toShow) cursor?.onPause() else cursor?.onResume()
        cursor?.setEnabledForCurrentState()
        if (toShow) MenuInteractionMonitor.menuOpened() else MenuInteractionMonitor.menuClosed()
    }

    private inner class NavigationStateProvider : BrowserNavigationOverlay.BrowserNavigationStateProvider {
        override fun isBackEnabled() = session.canGoBack
        override fun isForwardEnabled() = session.canGoForward
        override fun isPinEnabled() = !isUrlEqualToHomepage
        override fun isRefreshEnabled() = !isUrlEqualToHomepage
        override fun getCurrentUrl() = session.url
        override fun isURLPinned() = session.url.toUri()?.let {
            // TODO: #569 fix CustomTilesManager to use Uri too
            CustomTilesManager.getInstance(context!!).isURLPinned(it.toString()) ||
                    BundledTilesManager.getInstance(context!!).isURLPinned(it) } ?: false
    }
}
