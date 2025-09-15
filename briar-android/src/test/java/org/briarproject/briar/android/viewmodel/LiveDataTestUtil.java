/* Copyright 2019 Google LLC.
   SPDX-License-Identifier: Apache-2.0
   https://gist.github.com/JoseAlcerreca/1e9ee05dcdd6a6a6fa1cbfc125559bba
   */

package org.briarproject.briar.android.viewmodel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

public class LiveDataTestUtil {
	public static <T> T getOrAwaitValue(final LiveData<T> liveData)
			throws InterruptedException {
		final AtomicReference<T> data = new AtomicReference<>();
		final CountDownLatch latch = new CountDownLatch(1);
		Observer<T> observer = new Observer<T>() {
			@Override
			public void onChanged(@Nullable T o) {
				data.set(o);
				latch.countDown();
				liveData.removeObserver(this);
			}
		};
		liveData.observeForever(observer);
		// Don't wait indefinitely if the LiveData is not set.
		if (!latch.await(2, TimeUnit.SECONDS)) {
			throw new RuntimeException("LiveData value was never set.");
		}
		return data.get();
	}
}
