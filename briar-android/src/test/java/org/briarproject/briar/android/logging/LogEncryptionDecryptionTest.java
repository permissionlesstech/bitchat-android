package org.briarproject.briar.android.logging;

import org.briarproject.bramble.test.BrambleMockTestCase;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static org.briarproject.bramble.util.StringUtils.getRandomString;
import static org.briarproject.briar.android.logging.BriefLogFormatter.formatLog;
import static org.junit.Assert.assertEquals;

public class LogEncryptionDecryptionTest extends BrambleMockTestCase {

	@ClassRule
	public static TemporaryFolder folder = new TemporaryFolder();

	private final SecureRandom random;
	private final CachingLogHandler cachingLogHandler;
	private final LogEncrypter logEncrypter;
	private final LogDecrypter logDecrypter;
	private final BriefLogFormatter logFormatter = new BriefLogFormatter();

	public LogEncryptionDecryptionTest() throws IOException {
		LoggingComponent loggingComponent = DaggerLoggingComponent.builder()
				.loggingTestModule(new LoggingTestModule(folder.newFile()))
				.build();
		random = loggingComponent.random();
		logEncrypter = loggingComponent.logEncrypter();
		logDecrypter = loggingComponent.logDecrypter();
		cachingLogHandler = loggingComponent.cachingLogHandler();
	}

	@Test
	public void testEncryptedMatchesDecrypted() {
		ArrayList<LogRecord> logRecords =
				new ArrayList<>(random.nextInt(99) + 1);
		for (int i = 0; i < logRecords.size(); i++) {
			LogRecord logRecord = getRandomLogRecord();
			cachingLogHandler.publish(logRecord);
			logRecords.add(logRecord);
		}
		byte[] logKey = logEncrypter.encryptLogs();
		assertEquals(formatLog(logFormatter, logRecords),
				logDecrypter.decryptLogs(logKey));
	}

	private LogRecord getRandomLogRecord() {
		Level[] levels = {SEVERE, WARNING, INFO, FINE};
		Level level = levels[random.nextInt(levels.length)];
		LogRecord logRecord =
				new LogRecord(level, getRandomString(random.nextInt(128) + 1));
		logRecord.setLoggerName(getRandomString(random.nextInt(23) + 1));
		return logRecord;
	}

}
