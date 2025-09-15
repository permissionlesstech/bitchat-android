package org.briarproject.briar.android.contact.connect;

import androidx.annotation.StringRes;

abstract class ConnectViaBluetoothState {

	static class Connecting extends ConnectViaBluetoothState {
	}

	static class Success extends ConnectViaBluetoothState {
	}

	static class Error extends ConnectViaBluetoothState {
		@StringRes
		final int errorRes;

		Error(@StringRes int errorRes) {
			this.errorRes = errorRes;
		}
	}

}

