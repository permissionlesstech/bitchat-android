package org.briarproject.briar.android;

import android.net.TrafficStats;
import android.os.Process;

import org.briarproject.briar.api.android.NetworkUsageMetrics;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static org.briarproject.bramble.util.LogUtils.now;

@NotNullByDefault
class NetworkUsageMetricsImpl implements NetworkUsageMetrics {

	private static final Logger LOG =
			Logger.getLogger(NetworkUsageMetricsImpl.class.getName());

	private volatile long startTime, rxBytes, txBytes;

	@Override
	public void startService() {
		startTime = now();
		int uid = Process.myUid();
		rxBytes = TrafficStats.getUidRxBytes(uid);
		txBytes = TrafficStats.getUidTxBytes(uid);
	}

	@Override
	public void stopService() {
		if (LOG.isLoggable(INFO)) {
			Metrics metrics = getMetrics();
			LOG.info("Duration " + (metrics.getSessionDurationMs() / 1000)
					+ " seconds");
			LOG.info("Received " + metrics.getRxBytes() + " bytes");
			LOG.info("Sent " + metrics.getTxBytes() + " bytes");
		}
	}

	@Override
	public Metrics getMetrics() {
		long sessionDurationMs = now() - startTime;
		int uid = Process.myUid();
		long rx = TrafficStats.getUidRxBytes(uid) - rxBytes;
		long tx = TrafficStats.getUidTxBytes(uid) - txBytes;
		return new Metrics(sessionDurationMs, rx, tx);
	}
}
