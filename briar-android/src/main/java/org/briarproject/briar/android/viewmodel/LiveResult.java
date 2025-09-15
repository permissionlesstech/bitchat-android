package org.briarproject.briar.android.viewmodel;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

@NotNullByDefault
public class LiveResult<T> {

	@Nullable
	private final T result;
	@Nullable
	private final Exception exception;

	public LiveResult(T result) {
		this.result = result;
		this.exception = null;
	}

	public LiveResult(Exception exception) {
		this.result = null;
		this.exception = exception;
	}

	@Nullable
	public T getResultOrNull() {
		return result;
	}

	@Nullable
	public Exception getException() {
		return exception;
	}

	public boolean hasError() {
		return exception != null;
	}

	/**
	 * Runs the given function, if {@link #hasError()} is true.
	 */
	public LiveResult<T> onError(Consumer<Exception> fun) {
		if (exception != null) fun.accept(exception);
		return this;
	}

	/**
	 * Runs the given function, if {@link #hasError()} is false.
	 */
	public LiveResult<T> onSuccess(Consumer<T> fun) {
		if (result != null) fun.accept(result);
		return this;
	}

}
