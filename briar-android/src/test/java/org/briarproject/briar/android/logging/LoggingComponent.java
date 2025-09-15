package org.briarproject.briar.android.logging;

import org.briarproject.bramble.BrambleCoreModule;
import org.briarproject.bramble.system.ClockModule;
import org.briarproject.bramble.test.TestSecureRandomModule;

import java.security.SecureRandom;

import javax.inject.Singleton;

import dagger.Component;

@Singleton
@Component(modules = {
		ClockModule.class,
		BrambleCoreModule.class,
		TestSecureRandomModule.class,
		LoggingModule.class,
		LoggingTestModule.class,
})
public interface LoggingComponent {

	SecureRandom random();

	CachingLogHandler cachingLogHandler();

	LogEncrypter logEncrypter();

	LogDecrypter logDecrypter();

}
