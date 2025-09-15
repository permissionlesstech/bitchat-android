package org.briarproject.briar.android.logging;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class LoggingModule {

	@Provides
	@Singleton
	CachingLogHandler provideCachingLogHandler() {
		return new CachingLogHandler();
	}

	@Provides
	@Singleton
	LogEncrypter provideLogEncrypter(LogEncrypterImpl logEncrypter) {
		return logEncrypter;
	}

	@Provides
	@Singleton
	LogDecrypter provideLogDecrypter(LogDecrypterImpl logDecrypter) {
		return logDecrypter;
	}

}
