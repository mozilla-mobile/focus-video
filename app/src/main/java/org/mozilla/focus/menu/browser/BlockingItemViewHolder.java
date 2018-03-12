/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.menu.browser;

import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import org.mozilla.focus.R;
import org.mozilla.focus.fragment.BrowserFragment;
import org.mozilla.focus.utils.ThreadUtils;

/* package */ class BlockingItemViewHolder extends BrowserMenuViewHolder implements CompoundButton.OnCheckedChangeListener {
    /* package */ static final int LAYOUT_ID = R.layout.menu_blocking_switch;

    private TextView trackerCounter;
    private BrowserFragment fragment;

    /* package */ BlockingItemViewHolder(View itemView, final BrowserFragment fragment) {
        super(itemView);

        this.fragment = fragment;

        final Switch switchView = itemView.findViewById(R.id.blocking_switch);
        switchView.setChecked(fragment.getSession().isBlockingEnabled());
        switchView.setOnCheckedChangeListener(this);

        trackerCounter = itemView.findViewById(R.id.trackers_count);
    }

    /* package */ void updateTrackers(int trackers) {
        if (fragment.getSession().isBlockingEnabled()) {
            updateTrackingCount(trackerCounter, trackers);
        } else {
            disableTrackingCount(trackerCounter);
        }
    }

    private void updateTrackingCount(final TextView view, final int count) {
        ThreadUtils.postToMainThread(new Runnable() {
            @Override
            public void run() {
                view.setText(String.valueOf(count));
            }
        });
    }

    private void disableTrackingCount(final TextView view) {
        ThreadUtils.postToMainThread(new Runnable() {
            @Override
            public void run() {
                view.setText(R.string.content_blocking_disabled);
            }
        });
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        fragment.getWebView().setBlockingEnabled(isChecked);

        // Delay closing the menu and reloading the website a bit so that the user can actually see
        // the switch change its state.
        ThreadUtils.postToMainThreadDelayed(new Runnable() {
            @Override
            public void run() {
                getMenu().dismiss();

                fragment.getWebView().reload();
            }
        }, /* Switch.THUMB_ANIMATION_DURATION */ 250);
    }
}
