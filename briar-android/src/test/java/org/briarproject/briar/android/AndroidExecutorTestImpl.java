package org.briarproject.briar.android;

import org.briarproject.bramble.api.system.AndroidExecutor;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

public class AndroidExecutorTestImpl implements AndroidExecutor {

	private final Executor executor;

	public AndroidExecutorTestImpl(Executor executor) {
		this.executor = executor;
	}

	@Override
	public <V> Future<V> runOnBackgroundThread(Callable<V> c) {
		throw new IllegalStateException("not implemented");
	}

	@Override
	public void runOnBackgroundThread(Runnable r) {
		executor.execute(r);
	}

	@Override
	public <V> Future<V> runOnUiThread(Callable<V> c) {
		throw new IllegalStateException("not implemented");
	}

	@Override
	public void runOnUiThread(Runnable r) {
		executor.execute(r);
	}
}
