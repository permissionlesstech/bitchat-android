/* Copyright 2019 Google LLC.
   SPDX-License-Identifier: Apache-2.0
   https://gist.github.com/JoseAlcerreca/1e9ee05dcdd6a6a6fa1cbfc125559bba
   */

package org.briarproject.briar.android.viewmodel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class LiveEventTestUtil {
	public static <T> T getOrAwaitValue(final LiveEvent<T> liveEvent)
			throws InterruptedException {
		final AtomicReference<T> data = new AtomicReference<>();
		final CountDownLatch latch = new CountDownLatch(1);
		liveEvent.observeEventForever(new LiveEvent.LiveEventHandler<T>() {
			@Override
			public void onEvent(T o) {
				data.set(o);
				latch.countDown();
				// LiveEventHandler is wrapped internally in an observer that we
				// don't have a reference to. Skip trying to remove the observer
				// for now; all is torn down at the end of testing anyway.
			}
		});

		// Don't wait indefinitely if the LiveEvent is not set.
		if (!latch.await(2, TimeUnit.SECONDS)) {
			throw new RuntimeException("LiveEvent value was never set.");
		}
		return data.get();
	}
}
