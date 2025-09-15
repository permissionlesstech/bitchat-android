package org.briarproject.briar.api.android;

import org.briarproject.bramble.api.lifecycle.Service;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface NetworkUsageMetrics extends Service {

	Metrics getMetrics();

	class Metrics {

		private final long sessionDurationMs, rxBytes, txBytes;

		public Metrics(long sessionDurationMs, long rxBytes,
				long txBytes) {
			this.sessionDurationMs = sessionDurationMs;
			this.rxBytes = rxBytes;
			this.txBytes = txBytes;
		}

		public long getSessionDurationMs() {
			return sessionDurationMs;
		}

		public long getRxBytes() {
			return rxBytes;
		}

		public long getTxBytes() {
			return txBytes;
		}
	}
}
