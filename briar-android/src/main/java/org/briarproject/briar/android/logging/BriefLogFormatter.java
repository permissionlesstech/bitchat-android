package org.briarproject.briar.android.logging;

import org.briarproject.nullsafety.NotNullByDefault;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.TimeZone;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

import javax.annotation.concurrent.ThreadSafe;

import static java.util.Locale.US;

@ThreadSafe
@NotNullByDefault
public class BriefLogFormatter extends Formatter {

	public static String formatLog(Formatter formatter,
			Collection<LogRecord> logRecords) {
		StringBuilder sb = new StringBuilder();
		for (LogRecord record : logRecords) {
			String formatted = formatter.format(record);
			sb.append(formatted).append('\n');
		}
		return sb.toString();
	}

	private final Object lock = new Object();
	private final DateFormat dateFormat; // Locking: lock
	private final Date date; // Locking: lock

	public BriefLogFormatter() {
		synchronized (lock) {
			dateFormat = new SimpleDateFormat("MM-dd HH:mm:ss.SSS ", US);
			dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
			date = new Date();
		}
	}

	@Override
	public String format(LogRecord record) {
		String dateString;
		synchronized (lock) {
			date.setTime(record.getMillis());
			dateString = dateFormat.format(date);
		}
		StringBuilder sb = new StringBuilder(dateString);
		sb.append(record.getLevel().getName().charAt(0)).append('/');
		String tag = record.getLoggerName();
		tag = tag.substring(tag.lastIndexOf('.') + 1);
		sb.append(tag).append(": ");
		sb.append(record.getMessage());
		Throwable t = record.getThrown();
		if (t != null) {
			sb.append('\n');
			appendThrowable(sb, t);
		}
		return sb.toString();
	}

	private void appendThrowable(StringBuilder sb, Throwable t) {
		sb.append(t);
		for (StackTraceElement e : t.getStackTrace())
			sb.append("\n        at ").append(e);
		Throwable cause = t.getCause();
		if (cause != null) {
			sb.append("\n     Caused by: ");
			appendThrowable(sb, cause);
		}
	}
}
