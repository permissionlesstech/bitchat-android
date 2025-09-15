package org.briarproject.briar.android.logging;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.reporting.DevConfig;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.bramble.api.transport.StreamWriterFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.Nullable;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logException;

@NotNullByDefault
class LogEncrypterImpl implements LogEncrypter {

	private static final Logger LOG =
			getLogger(LogEncrypterImpl.class.getName());

	private final DevConfig devConfig;
	private final CachingLogHandler logHandler;
	private final CryptoComponent crypto;
	private final StreamWriterFactory streamWriterFactory;

	@Inject
	LogEncrypterImpl(DevConfig devConfig,
			CachingLogHandler logHandler,
			CryptoComponent crypto,
			StreamWriterFactory streamWriterFactory) {
		this.devConfig = devConfig;
		this.logHandler = logHandler;
		this.crypto = crypto;
		this.streamWriterFactory = streamWriterFactory;
	}

	@Nullable
	@Override
	public byte[] encryptLogs() {
		SecretKey logKey = crypto.generateSecretKey();
		File logFile = devConfig.getLogcatFile();
		try (OutputStream out = new FileOutputStream(logFile)) {
			StreamWriter streamWriter =
					streamWriterFactory.createLogStreamWriter(out, logKey);
			Writer writer =
					new OutputStreamWriter(streamWriter.getOutputStream());
			writeLogString(writer);
			writer.close();
			return logKey.getBytes();
		} catch (IOException e) {
			logException(LOG, WARNING, e);
			return null;
		}
	}

	private void writeLogString(Writer writer) throws IOException {
		Formatter formatter = new BriefLogFormatter();
		for (LogRecord record : logHandler.getRecentLogRecords()) {
			String formatted = formatter.format(record);
			writer.append(formatted).append('\n');
		}
	}

}
