package org.briarproject.briar.android.reporting;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import org.briarproject.bramble.api.plugin.Plugin;
import org.briarproject.bramble.api.plugin.PluginManager;
import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.reporting.DevReporter;
import org.briarproject.bramble.util.AndroidUtils;
import org.briarproject.briar.R;
import org.briarproject.briar.android.logging.BriefLogFormatter;
import org.briarproject.briar.android.logging.CachingLogHandler;
import org.briarproject.briar.android.logging.LogDecrypter;
import org.briarproject.briar.android.reporting.ReportData.MultiReportInfo;
import org.briarproject.briar.android.reporting.ReportData.ReportItem;
import org.briarproject.briar.android.viewmodel.LiveEvent;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.briarproject.briar.api.android.MemoryStats;
import org.briarproject.briar.api.android.NetworkUsageMetrics;
import org.briarproject.nullsafety.NotNullByDefault;
import org.json.JSONException;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.UUID;
import java.util.logging.Formatter;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.StringUtils.isNullOrEmpty;
import static org.briarproject.briar.android.logging.BriefLogFormatter.formatLog;

@NotNullByDefault
class ReportViewModel extends AndroidViewModel {

	private static final Logger LOG =
			getLogger(ReportViewModel.class.getName());

	private final CachingLogHandler logHandler;
	private final LogDecrypter logDecrypter;
	private final BriarReportCollector collector;
	private final DevReporter reporter;
	private final PluginManager pluginManager;

	private final MutableLiveEvent<Boolean> showReport =
			new MutableLiveEvent<>();
	private final MutableLiveData<Boolean> showReportData =
			new MutableLiveData<>();
	private final MutableLiveData<ReportData> reportData =
			new MutableLiveData<>();
	private final MutableLiveEvent<Integer> closeReport =
			new MutableLiveEvent<>();
	private boolean isFeedback;
	@Nullable
	private String initialComment;

	@Inject
	ReportViewModel(@NonNull Application application,
			NetworkUsageMetrics networkUsageMetrics,
			CachingLogHandler logHandler,
			LogDecrypter logDecrypter,
			DevReporter reporter,
			PluginManager pluginManager) {
		super(application);
		collector = new BriarReportCollector(application, networkUsageMetrics);
		this.logHandler = logHandler;
		this.logDecrypter = logDecrypter;
		this.reporter = reporter;
		this.pluginManager = pluginManager;
	}

	void init(@Nullable Throwable t, long appStartTime,
			@Nullable byte[] logKey, @Nullable String initialComment,
			MemoryStats memoryStats) {
		this.initialComment = initialComment;
		isFeedback = t == null;
		if (reportData.getValue() == null) new SingleShotAndroidExecutor(() -> {
			String decryptedLogs;
			if (isFeedback) {
				Formatter formatter = new BriefLogFormatter();
				decryptedLogs =
						formatLog(formatter, logHandler.getRecentLogRecords());
			} else {
				decryptedLogs = logDecrypter.decryptLogs(logKey);
				if (decryptedLogs == null) {
					// error decrypting logs, get logs from this process
					Formatter formatter = new BriefLogFormatter();
					decryptedLogs = formatLog(formatter,
							logHandler.getRecentLogRecords());
				}
			}
			ReportData data = collector.collectReportData(t, appStartTime,
					decryptedLogs, memoryStats);
			reportData.postValue(data);
		}).start();
	}

	@Nullable
	String getInitialComment() {
		return initialComment;
	}

	boolean isFeedback() {
		return isFeedback;
	}

	/**
	 * Call this from the crash screen, if the user wants to report a crash.
	 */
	@UiThread
	void showReport() {
		showReport.setEvent(true);
	}

	/**
	 * Will be set to true when the user wants to report a crash.
	 */
	LiveEvent<Boolean> getShowReport() {
		return showReport;
	}

	/**
	 * The report data will be made visible in the UI when visible is true,
	 * otherwise hidden.
	 */
	@UiThread
	void showReportData(boolean visible) {
		showReportData.setValue(visible);
	}

	/**
	 * Will be set to true when the user wants to see report data.
	 */
	LiveData<Boolean> getShowReportData() {
		return showReportData;
	}

	/**
	 * The content of the report that will be loaded after
	 * {@link #init(Throwable, long, byte[], String, MemoryStats)} was called.
	 */
	LiveData<ReportData> getReportData() {
		return reportData;
	}

	/**
	 * Sends reports and returns now if reports are being sent now
	 * or false, if reports will be sent next time TorPlugin becomes active.
	 */
	@UiThread
	boolean sendReport(String comment, String email, boolean includeReport) {
		ReportData data = requireNonNull(reportData.getValue());
		if (!isNullOrEmpty(comment) || isNullOrEmpty(email)) {
			MultiReportInfo userInfo = new MultiReportInfo();
			if (!isNullOrEmpty(comment)) userInfo.add("Comment", comment);
			if (!isNullOrEmpty(email)) userInfo.add("Email", email);
			data.add(new ReportItem("UserInfo", R.string.dev_report_user_info,
					userInfo, false));
		}

		// check the state of the TorPlugin, if this is feedback
		boolean sendFeedbackNow;
		if (isFeedback) {
			Plugin plugin = pluginManager.getPlugin(TorConstants.ID);
			sendFeedbackNow = plugin != null && plugin.getState() == ACTIVE;
		} else {
			sendFeedbackNow = false;
		}

		Runnable reportSender =
				getReportSender(includeReport, data, sendFeedbackNow);
		new SingleShotAndroidExecutor(reportSender).start();
		return sendFeedbackNow;
	}

	private Runnable getReportSender(boolean includeReport, ReportData data,
			boolean sendFeedbackNow) {
		return () -> {
			boolean error = false;
			try {
				File reportDir = AndroidUtils.getReportDir(getApplication());
				String reportId = UUID.randomUUID().toString();
				String report = data.toJson(includeReport).toString();
				reporter.encryptReportToFile(reportDir, reportId, report);
			} catch (FileNotFoundException | JSONException e) {
				logException(LOG, WARNING, e);
				error = true;
			}

			int stringRes;
			if (error) {
				stringRes = R.string.dev_report_error;
			} else if (sendFeedbackNow) {
				boolean sent = reporter.sendReports() > 0;
				stringRes = sent ?
						R.string.dev_report_sent : R.string.dev_report_saved;
			} else {
				stringRes = R.string.dev_report_saved;
			}
			closeReport.postEvent(stringRes);
		};
	}

	@UiThread
	void closeReport() {
		closeReport.setEvent(0);
	}

	/**
	 * An integer representing a string resource
	 * informing about the outcome of the report
	 * or 0 if no information is required, such as when back button was pressed.
	 */
	LiveEvent<Integer> getCloseReport() {
		return closeReport;
	}

	// Used for a new thread as the Android executor thread may have died
	private static class SingleShotAndroidExecutor extends Thread {

		private final Runnable runnable;

		private SingleShotAndroidExecutor(Runnable runnable) {
			this.runnable = runnable;
		}

		@Override
		public void run() {
			Looper.prepare();
			Handler handler = new Handler();
			handler.post(runnable);
			handler.post(() -> {
				Looper looper = Looper.myLooper();
				if (looper != null) looper.quit();
			});
			Looper.loop();
		}
	}

}
