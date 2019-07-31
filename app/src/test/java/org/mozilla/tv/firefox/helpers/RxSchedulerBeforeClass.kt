/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.tv.firefox.helpers

import io.reactivex.Scheduler
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.functions.Function
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.TestScheduler
import org.junit.BeforeClass
import org.junit.rules.ExternalResource
import java.util.concurrent.Callable

/**
 * Used to force all tested Rx code to execute synchronously.
 *
 * IMPORTANT NOTE: **must** be called from [BeforeClass]
 */
fun forceRxSynchronous() {
    setRxScheduler(Schedulers.trampoline())
}

/**
 * Used to force all tested Rx code that changes threads to operate on the same
 * [TestScheduler].
 *
 * IMPORTANT NOTE: **must** be called from [BeforeClass]
 *
 * @return the [TestScheduler] to which all observeOn and subscribeOn calls will
 * be forwarded.
 */
fun forceRxTestScheduler(): TestScheduler {
    val testScheduler = TestScheduler()
    setRxScheduler(testScheduler)
    return testScheduler
}

private fun setRxScheduler(scheduleTo: Scheduler) {
    val initHandler = Function<Callable<Scheduler>, Scheduler> { scheduleTo }
    val setHandler = Function<Scheduler, Scheduler> { scheduleTo }

    RxJavaPlugins.setInitIoSchedulerHandler(initHandler)
    RxJavaPlugins.setIoSchedulerHandler(setHandler)

    RxJavaPlugins.setInitComputationSchedulerHandler(initHandler)
    RxJavaPlugins.setComputationSchedulerHandler(setHandler)

    RxJavaPlugins.setInitNewThreadSchedulerHandler(initHandler)
    RxJavaPlugins.setNewThreadSchedulerHandler(setHandler)

    RxJavaPlugins.setInitSingleSchedulerHandler(initHandler)
    RxJavaPlugins.setSingleSchedulerHandler(setHandler)

    RxAndroidPlugins.setInitMainThreadSchedulerHandler(initHandler)
    RxAndroidPlugins.setMainThreadSchedulerHandler(setHandler)
}
