package org.briarproject.briar.android;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import javax.annotation.concurrent.Immutable;

/**
 * Log handler that raises all records at or above a given source level to a
 * given destination level. This affects the level seen by subsequent handlers.
 */
@Immutable
@NotNullByDefault
class LevelRaisingHandler extends Handler {

	private final Level dest;
	private final int srcInt, destInt;

	LevelRaisingHandler(Level src, Level dest) {
		this.dest = dest;
		srcInt = src.intValue();
		destInt = dest.intValue();
		if (srcInt > destInt) throw new IllegalArgumentException();
	}

	@Override
	public void publish(LogRecord record) {
		int recordInt = record.getLevel().intValue();
		if (recordInt >= srcInt && recordInt < destInt) record.setLevel(dest);
	}

	@Override
	public void flush() {
	}

	@Override
	public void close() {
	}
}
