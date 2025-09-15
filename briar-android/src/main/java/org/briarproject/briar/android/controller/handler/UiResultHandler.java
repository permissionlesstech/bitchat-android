package org.briarproject.briar.android.controller.handler;

import org.briarproject.briar.android.DestroyableContext;

import androidx.annotation.UiThread;

public abstract class UiResultHandler<R> implements ResultHandler<R> {

	private final DestroyableContext listener;

	protected UiResultHandler(DestroyableContext listener) {
		this.listener = listener;
	}

	@Override
	public void onResult(R result) {
		listener.runOnUiThreadUnlessDestroyed(() -> onResultUi(result));
	}

	@UiThread
	public abstract void onResultUi(R result);
}
