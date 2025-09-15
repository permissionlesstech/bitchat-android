package org.briarproject.briar.android.viewmodel;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class MutableLiveEvent<T> extends LiveEvent<T> {

	/**
	 * Creates a MutableLiveEvent initialized with the given {@code value}.
	 *
	 * @param value initial value
	 */
	public MutableLiveEvent(T value) {
		super(value);
	}

	/**
	 * Creates a MutableLiveEvent with no value assigned to it.
	 */
	public MutableLiveEvent() {
		super();
	}

	public void postEvent(T value) {
		super.postValue(new ConsumableEvent<>(value));
	}

	public void setEvent(T value) {
		super.setValue(new ConsumableEvent<>(value));
	}
}
