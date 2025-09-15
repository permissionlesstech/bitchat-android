package org.briarproject.briar.android.hotspot;

import android.graphics.Bitmap;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

@NotNullByDefault
abstract class HotspotState {

	static class StartingHotspot extends HotspotState {
	}

	static class NetworkConfig {
		final String ssid, password;
		@Nullable
		final Bitmap qrCode;

		NetworkConfig(String ssid, String password, @Nullable Bitmap qrCode) {
			this.ssid = ssid;
			this.password = password;
			this.qrCode = qrCode;
		}
	}

	static class WebsiteConfig {
		final String url;
		@Nullable
		final Bitmap qrCode;

		WebsiteConfig(String url, @Nullable Bitmap qrCode) {
			this.url = url;
			this.qrCode = qrCode;
		}
	}

	static class HotspotStarted extends HotspotState {
		private final NetworkConfig networkConfig;
		private final WebsiteConfig websiteConfig;
		// 'consumed' is set to true once this state triggered a UI change, i.e.
		// moving to the next fragment.
		private boolean consumed = false;

		HotspotStarted(NetworkConfig networkConfig,
				WebsiteConfig websiteConfig) {
			this.networkConfig = networkConfig;
			this.websiteConfig = websiteConfig;
		}

		NetworkConfig getNetworkConfig() {
			return networkConfig;
		}

		WebsiteConfig getWebsiteConfig() {
			return websiteConfig;
		}

		@UiThread
		boolean wasNotYetConsumed() {
			return !consumed;
		}

		/**
		 * Mark this state as consumed, i.e. the UI has already done something
		 * as a result of the state changing to this. This can be used in order
		 * to not repeat actions such as showing fragments on rotation changes.
		 */
		@UiThread
		void consume() {
			consumed = true;
		}
	}

	static class HotspotError extends HotspotState {
		private final String error;

		HotspotError(String error) {
			this.error = error;
		}

		String getError() {
			return error;
		}
	}

}
