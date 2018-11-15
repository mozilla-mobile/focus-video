/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.tv.firefox.navigationoverlay

import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.support.annotation.StringRes
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import kotlinx.android.synthetic.main.browser_overlay.view.*
import kotlinx.android.synthetic.main.browser_overlay_top_nav.view.*
import kotlinx.android.synthetic.main.pocket_video_mega_tile.view.*
import kotlinx.coroutines.Job
import mozilla.components.support.ktx.android.view.hideKeyboard
import org.mozilla.tv.firefox.R
import org.mozilla.tv.firefox.components.UrlAutoCompleteFilter
import org.mozilla.tv.firefox.ext.forEachChild
import org.mozilla.tv.firefox.ext.forceExhaustive
import org.mozilla.tv.firefox.ext.isEffectivelyVisible
import org.mozilla.tv.firefox.ext.isVisible
import org.mozilla.tv.firefox.ext.updateLayoutParams
import org.mozilla.tv.firefox.widget.IgnoreFocusMovementMethod
import org.mozilla.tv.firefox.widget.InlineAutocompleteEditText
import kotlin.properties.Delegates
import org.mozilla.tv.firefox.ext.isVoiceViewEnabled
import org.mozilla.tv.firefox.ext.serviceLocator
import org.mozilla.tv.firefox.pinnedtile.PinnedTileAdapter
import org.mozilla.tv.firefox.pinnedtile.PinnedTileViewModel
import org.mozilla.tv.firefox.pocket.PocketViewModel
import org.mozilla.tv.firefox.toolbar.ToolbarViewModel
import org.mozilla.tv.firefox.utils.AppConstants
import org.mozilla.tv.firefox.utils.ViewUtils
import java.lang.ref.WeakReference

private const val NAVIGATION_BUTTON_ENABLED_ALPHA = 1.0f
private const val NAVIGATION_BUTTON_DISABLED_ALPHA = 0.3f

private const val SHOW_UNPIN_TOAST_COUNTER_PREF = "show_upin_toast_counter"
private const val MAX_UNPIN_TOAST_COUNT = 3

private const val COL_COUNT = 5
private val uiHandler = Handler(Looper.getMainLooper())

enum class NavigationEvent {
    SETTINGS, BACK, FORWARD, RELOAD, LOAD_URL, LOAD_TILE, TURBO, PIN_ACTION, POCKET, DESKTOP_MODE;

    companion object {
        fun fromViewClick(viewId: Int?) = when (viewId) {
            R.id.navButtonBack -> BACK
            R.id.navButtonForward -> FORWARD
            R.id.navButtonReload -> RELOAD
            R.id.navButtonSettings -> SETTINGS
            R.id.turboButton -> TURBO
            R.id.pinButton -> PIN_ACTION
            R.id.pocketVideoMegaTileView -> POCKET
            R.id.desktopModeButton -> DESKTOP_MODE
            else -> null
        }
    }
}

@Suppress("LargeClass") // TODO remove this. See https://github.com/mozilla-mobile/firefox-tv/issues/1187
class BrowserNavigationOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle), View.OnClickListener {

    enum class ParentFragment {
        SETTINGS,
        POCKET,
        DEFAULT
    }

    sealed class Action {
        data class ShowTopToast(@StringRes val textId: Int) : Action()
        data class ShowBottomToast(@StringRes val textId: Int) : Action()
        data class SetOverlayVisible(val visible: Boolean) : Action()
    }

    /**
     * Used to cancel background->UI threads: we attach them as children to this job
     * and cancel this job at the end of the UI lifecycle, cancelling the children.
     */
    var uiLifecycleCancelJob: Job

    // We need this in order to show the unpin toast, at max, once per
    // instantiation of the BrowserNavigationOverlay
    var canShowUpinToast: Boolean = false

    // Setting the onTileLongClick function in the HomeTileAdapter is fragile
    // since we init the tiles in View.init and Android is inflating the view for us,
    // thus we need to use Delegates.observable to update onTileLongClick.
    var openHomeTileContextMenu: (() -> Unit) by Delegates.observable({}) { _, _, newValue ->
        with(tileContainer) {
            (adapter as PinnedTileAdapter).onTileLongClick = newValue
        }
    }

    var onNavigationEvent: ((
        event: NavigationEvent,
        value: String?,
        autocompleteResult: InlineAutocompleteEditText.AutocompleteResult?
    ) -> Unit)? = null

    var onPreSetVisibilityListener: ((isVisible: Boolean) -> Unit)? = null

    var setOverlayVisible: ((Boolean) -> Unit)? = null // TODO once overlay is a fragment, remove this and handle with a transaction

    var parentFrag = ParentFragment.DEFAULT

    private var hasUserChangedURLSinceEditTextFocused = false

    private lateinit var tileAdapter: PinnedTileAdapter
    private lateinit var pocketViewModel: PocketViewModel
    private lateinit var toolbarViewModel: ToolbarViewModel

    lateinit var pinnedTileViewModel: PinnedTileViewModel
    lateinit var lifeCycleOwner: LifecycleOwner

    init {
        LayoutInflater.from(context)
                .inflate(R.layout.browser_overlay, this, true)

        topNavContainer.forEachChild {
            it.nextFocusDownId = navUrlInput.id
            if (it.isFocusable) it.setOnClickListener(this)
        }

        uiLifecycleCancelJob = Job()

        initMegaTile()
        setupUrlInput()
        navButtonSettings.setImageResource(R.drawable.ic_settings) // Must be set in code for SVG to work correctly.

        val tintDrawable: (Drawable?) -> Unit = { it?.setTint(ContextCompat.getColor(context, R.color.photonGrey10_a60p)) }
        navUrlInput.compoundDrawablesRelative.forEach(tintDrawable)
    }

    // FIXME: Called from [WebRenderFragment] until NavigationOverlayFragment breakout
    fun initPinnedTiles() = with(tileContainer) {
        canShowUpinToast = true

        // TODO: pass in VM live data instead of "homeTiles"
        tileAdapter = PinnedTileAdapter(uiLifecycleCancelJob, loadUrl = { urlStr ->
            with(navUrlInput) {
                if (urlStr.isNotEmpty()) {
                    onNavigationEvent?.invoke(NavigationEvent.LOAD_TILE, urlStr, null)
                }
            }
        }, onTileLongClick = openHomeTileContextMenu, onTileFocused = {
            val prefInt = PreferenceManager.getDefaultSharedPreferences(context).getInt(SHOW_UNPIN_TOAST_COUNTER_PREF, 0)
            if (prefInt < MAX_UNPIN_TOAST_COUNT && canShowUpinToast) {
                PreferenceManager.getDefaultSharedPreferences(context)
                        .edit()
                        .putInt(SHOW_UNPIN_TOAST_COUNTER_PREF, prefInt + 1)
                        .apply()

                val contextReference = WeakReference(context)
                val showToast = showToast@{
                    val context = contextReference.get() ?: return@showToast
                    Toast.makeText(context, R.string.homescreen_unpin_tutorial_toast, Toast.LENGTH_LONG).show()
                }
                if (context.isVoiceViewEnabled()) uiHandler.postDelayed(showToast, 1500)
                else showToast.invoke()

                canShowUpinToast = false
            }
        })

        pinnedTileViewModel.getTileList().observe(lifeCycleOwner, Observer {
            if (it != null) {
                tileAdapter.setTiles(it)
                updateFocusableViews()
            }
        })

        adapter = tileAdapter

        layoutManager = HomeTileManager(context, COL_COUNT)

        setHasFixedSize(true)

        // We add bottomMargin to each tile in order to add spacing between them: this makes the
        // RecyclerView slightly larger than necessary and makes the default start screen scrollable
        // even though it doesn't need to be. To undo this, we add negative margins on the tile container.
        // I tried other solutions (ItemDecoration, dynamically changing margins) but this is more
        // complex because we need to relayout more than the changed view when adding/removing a row.
        val tileBottomMargin = resources.getDimensionPixelSize(R.dimen.home_tile_margin_bottom) -
                resources.getDimensionPixelSize(R.dimen.home_tile_container_margin_bottom)
        updateLayoutParams {
            val marginLayoutParams = it as MarginLayoutParams
            marginLayoutParams.bottomMargin = -tileBottomMargin
        }
    }

    /**
     * Used to show an error screen on the Pocket megatile when Pocket does not return any videos.
     */
    fun showMegaTileError() {
        pocketVideosContainer.visibility = View.GONE
        pocketErrorContainer.visibility = View.VISIBLE

        pocketMegaTileLoadError.text = resources.getString(R.string.pocket_video_feed_failed_to_load,
                resources.getString(R.string.pocket_brand_name))
        megaTileTryAgainButton.contentDescription = resources.getString(R.string.pocket_video_feed_failed_to_load,
                resources.getString(R.string.pocket_brand_name)) + " " + resources.getString(R.string.pocket_video_feed_reload_button)

        megaTileTryAgainButton.setOnClickListener { _ ->
            pocketViewModel.update()
            initMegaTile()
            updateFocusableViews()
            pocketVideoMegaTileView.requestFocus()
        }
        updateFocusAndURL()
    }

    private fun hideMegaTileError() {
        pocketVideosContainer.visibility = View.VISIBLE
        pocketErrorContainer.visibility = View.GONE
        updateFocusAndURL()
    }

    private fun initMegaTile() {
        pocketVideoMegaTileView.setOnClickListener(this)
    }

    fun observeForMegaTile(fragment: Fragment) {
        // TODO remove this
        // This function is necessary because BrowserNavigationOverlay is not a LifecycleOwner,
        // and so cannot subscribe to PocketRepo's LiveData. When the overlay is turned into a
        // fragment, this can be moved into the overlay fragment
        val factory = fragment.context!!.serviceLocator.viewModelFactory
        // TODO initialize pocketViewModel in onCreate when overlay is refactored into a fragment
        pocketViewModel = ViewModelProviders.of(fragment, factory).get(PocketViewModel::class.java)

        pocketViewModel.state.observe(fragment.viewLifecycleOwner, Observer { state ->
            when (state) {
                is PocketViewModel.State.Error -> {
                    pocketVideoMegaTileView.visibility = View.VISIBLE
                    showMegaTileError()
                }
                is PocketViewModel.State.Feed -> {
                    pocketVideoMegaTileView.visibility = View.VISIBLE
                    pocketVideoMegaTileView.setContent(state.feed)
                    hideMegaTileError()
                }
                is PocketViewModel.State.NotDisplayed -> pocketVideoMegaTileView.visibility = View.GONE
                null -> return@Observer
            }.forceExhaustive
        })
    }

    fun observeForToolbar(fragment: Fragment) {
        // TODO remove this
        // See comment in fun observeForMegaTile
        val factory = fragment.context!!.serviceLocator.viewModelFactory
        toolbarViewModel = ViewModelProviders.of(fragment, factory).get(ToolbarViewModel::class.java)

        fun updateOverlayButtonState(isEnabled: Boolean, overlayButton: ImageButton) {
            overlayButton.isEnabled = isEnabled
            overlayButton.isFocusable = isEnabled
            overlayButton.alpha =
                if (isEnabled) NAVIGATION_BUTTON_ENABLED_ALPHA else NAVIGATION_BUTTON_DISABLED_ALPHA
        }

        toolbarViewModel.state.observe(lifeCycleOwner, Observer {
            if (it == null) return@Observer
            val focusedView = findFocus()
            updateOverlayButtonState(it.backEnabled, navButtonBack)
            updateOverlayButtonState(it.forwardEnabled, navButtonForward)
            updateOverlayButtonState(it.pinEnabled, pinButton)
            updateOverlayButtonState(it.refreshEnabled, navButtonReload)
            updateOverlayButtonState(it.desktopModeEnabled, desktopModeButton)

            updateFocusableViews(focusedView)

            pinButton.isChecked = it.pinChecked
            desktopModeButton.isChecked = it.desktopModeChecked
            turboButton.isChecked = it.turboChecked

            if (!hasUserChangedURLSinceEditTextFocused) {
                // The url can get updated in the background, e.g. if a loading page is redirected. We
                // don't want a url update to interrupt the user typing so we don't update the url from
                // the background if the user has already updated the url themselves.
                //
                // We revert this state when the view is unfocused: it ensures the URL is usually accurate
                // (for security reasons) and it's simple compared to other options which keep more state.
                //
                // One problem this solution has is that if the URL is updated in the background rapidly,
                // sometimes key events will be dropped, but I don't think there's much we can do about this:
                // we can't determine if the keyboard is up or not and focus isn't a good indicator because
                // we can focus the EditText without opening the soft keyboard and the user won't even know
                // these are inaccurate!
                navUrlInput.setText(it.urlBarText)
            }
        })

        toolbarViewModel.events.observe(lifeCycleOwner, Observer {
            it?.consume {
                when (it) {
                    is BrowserNavigationOverlay.Action.ShowTopToast -> ViewUtils.showCenteredTopToast(context, it.textId)
                    is BrowserNavigationOverlay.Action.ShowBottomToast -> ViewUtils.showCenteredBottomToast(context, it.textId)
                    is BrowserNavigationOverlay.Action.SetOverlayVisible -> setOverlayVisible?.invoke(it.visible)
                }.forceExhaustive
                true
            }
        })
    }

    private fun setupUrlInput() = with(navUrlInput) {
        setOnCommitListener {
            val userInput = text.toString()
            if (userInput == AppConstants.APP_URL_HOME) {
                // If the input points to home, we short circuit and hide the keyboard, returning
                // the user to the home screen
                this.hideKeyboard()
                return@setOnCommitListener
            }

            if (userInput.isNotEmpty()) {
                val cachedAutocompleteResult = lastAutocompleteResult // setText clears the reference so we cache it here.
                setText(cachedAutocompleteResult.text)
                onNavigationEvent?.invoke(NavigationEvent.LOAD_URL, userInput, cachedAutocompleteResult)
            }
        }
        this.movementMethod = IgnoreFocusMovementMethod()
        val autocompleteFilter = UrlAutoCompleteFilter()
        autocompleteFilter.load(context.applicationContext, uiLifecycleCancelJob)
        setOnFilterListener { searchText, view -> autocompleteFilter.onFilter(searchText, view) }

        setOnUserInputListener { hasUserChangedURLSinceEditTextFocused = true }
        setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) hasUserChangedURLSinceEditTextFocused = false }
    }

    override fun onClick(view: View?) {
        val event = NavigationEvent.fromViewClick(view?.id)
                ?: return

        when (event) {
            NavigationEvent.BACK -> toolbarViewModel.backButtonClicked()
            NavigationEvent.FORWARD -> toolbarViewModel.forwardButtonClicked()
            NavigationEvent.RELOAD -> toolbarViewModel.reloadButtonClicked()
            NavigationEvent.PIN_ACTION -> toolbarViewModel.pinButtonClicked()
            NavigationEvent.TURBO -> toolbarViewModel.turboButtonClicked()
            NavigationEvent.DESKTOP_MODE -> toolbarViewModel.desktopModeButtonClicked()
            else -> Unit // Nothing to do.
        }
        onNavigationEvent?.invoke(event, null, null)
    }

    fun updateFocusableViews(focusedView: View? = findFocus()) { // TODO this will be replaced when FocusRepo is introduced
        val toolbarState = toolbarViewModel.state.value

        // Prevent the focus from looping to the bottom row when reaching the last
        // focusable element in the top row
        navButtonReload.nextFocusLeftId = when {
            toolbarState?.forwardEnabled == true -> R.id.navButtonForward
            toolbarState?.backEnabled == true -> R.id.navButtonBack
            else -> R.id.navButtonReload
        }
        navButtonForward.nextFocusLeftId = when {
            toolbarState?.backEnabled == true -> R.id.navButtonBack
            else -> R.id.navButtonForward
        }

        navUrlInput.nextFocusDownId = when {
            pocketViewModel.state.value is PocketViewModel.State.Feed -> R.id.pocketVideoMegaTileView
            pocketViewModel.state.value === PocketViewModel.State.Error -> R.id.megaTileTryAgainButton
            tileAdapter.itemCount == 0 -> R.id.navUrlInput
            else -> R.id.tileContainer
        }

        navUrlInput.nextFocusUpId = when {
            toolbarState?.backEnabled == true -> R.id.navButtonBack
            toolbarState?.forwardEnabled == true -> R.id.navButtonForward
            toolbarState?.refreshEnabled == true -> R.id.navButtonReload
            toolbarState?.pinEnabled == true -> R.id.pinButton
            else -> R.id.turboButton
        }

        pocketVideoMegaTileView.nextFocusDownId = when {
            tileAdapter.itemCount == 0 -> R.id.pocketVideoMegaTileView
            else -> R.id.tileContainer
        }

        // We may have lost focus when disabling the focused view above.
        // This looks more complex than is necessary, but the simpler implementation
        // led to problems. See the commit message for 45940fa
        val isFocusLost = focusedView != null && findFocus() == null
        if (isFocusLost) {
            navUrlInput.requestFocus()
        }
    }

    override fun setVisibility(visibility: Int) {
        onPreSetVisibilityListener?.invoke(visibility == View.VISIBLE)
        super.setVisibility(visibility)

        if (visibility == View.VISIBLE) {
            overlayScrollView.scrollTo(0, 0)
            when (parentFrag) {
                ParentFragment.SETTINGS -> {
                    navButtonSettings.requestFocus()
                }
                ParentFragment.POCKET -> {
                    pocketVideoMegaTileView.requestFocus()
                }
                ParentFragment.DEFAULT -> {
                    navUrlInput.requestFocus()
                }
            }
            updateFocusableViews()
        }
    }

    // TODO: OverlayVM should handle this instead after BrowserNavigationOverlay refactor
    fun updateTurboButton() {
        turboButton.isChecked = context.serviceLocator.turboMode.isEnabled()
    }

    /**
     * Focus may be lost if all pinned items are removed via onContextItemSelected()
     * FIXME: requires OverlayFragment (LifecycleOwner) -> OverlayVM -> FocusRepo
     */
    fun checkIfTilesFocusNeedRefresh() {
        if (tileAdapter.itemCount == 0) {
            if (pocketVideosContainer.isVisible) {
                pocketVideoMegaTileView.requestFocus()
            } else {
                megaTileTryAgainButton.requestFocus()
            }
        }
        updateFocusableViews()
    }

    inner class HomeTileManager(
        context: Context,
        colCount: Int
    ) : GridLayoutManager(context, colCount) {
        override fun onRequestChildFocus(parent: RecyclerView, state: RecyclerView.State, child: View, focused: View?): Boolean {
            var position = spanCount
            if (focused != null) {
                position = getPosition(focused)
            }

            // if position is less than spanCount, implies first row
            if (position < spanCount) {
                focused?.nextFocusUpId = when {
                    pocketVideosContainer.isEffectivelyVisible -> R.id.pocketVideoMegaTileView
                    megaTileTryAgainButton.isEffectivelyVisible -> R.id.megaTileTryAgainButton
                    else -> R.id.navUrlInput
                }
            }
            return super.onRequestChildFocus(parent, state, child, focused)
        }
    }
}

/**
 * A [ScrollView] with functionality overridden for the specific requirements of the overlay.
 *
 * One crappy thing with the current implementation is that when a scroll is interrupted (e.g. user
 * clicks up twice quickly), it will skip and not scroll smoothly. Since we don't scroll often,
 * this seems fine.
 */
class BrowserNavigationOverlayScrollView(
    context: Context,
    attrs: AttributeSet
) : ScrollView(context, attrs) {

    private val deltaScrollPadding = resources.getDimensionPixelSize(R.dimen.browser_overlay_delta_scroll_padding)

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int {
        // We modify the scroll offset to ensure:
        // 1) Scrolling through the tiles will show enough of the next tile to indicate scrollability.
        // 2) When focusing the last vertical view in the layout, the default implementation will
        //    leave some empty space at the edge of the view such that an additional dpad click will
        //    scroll the screen but nothing new is focused: we don't want that.
        val deltaScrollForOnScreen = super.computeScrollDeltaToGetChildRectOnScreen(rect)
        return deltaScrollForOnScreen + deltaScrollPadding * Integer.signum(deltaScrollForOnScreen)
    }
}
