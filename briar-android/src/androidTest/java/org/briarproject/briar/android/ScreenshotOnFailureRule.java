package org.briarproject.briar.android;

import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.IOException;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.test.espresso.Espresso;
import androidx.test.espresso.FailureHandler;
import androidx.test.espresso.base.DefaultFailureHandler;
import androidx.test.runner.screenshot.BasicScreenCaptureProcessor;
import androidx.test.runner.screenshot.ScreenCapture;
import androidx.test.runner.screenshot.ScreenCaptureProcessor;
import androidx.test.runner.screenshot.Screenshot;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

@NotNullByDefault
public class ScreenshotOnFailureRule implements TestRule {

	FailureHandler defaultFailureHandler =
			new DefaultFailureHandler(getApplicationContext());

	@Override
	public Statement apply(Statement base, Description description) {
		HashSet<ScreenCaptureProcessor> processors = new HashSet<>(1);
		processors.add(new BasicScreenCaptureProcessor());
		Screenshot.addScreenCaptureProcessors(processors);
		return new Statement() {
			@Override
			public void evaluate() throws Throwable {
				AtomicBoolean errorHandled = new AtomicBoolean(false);
				Espresso.setFailureHandler((throwable, matcher) -> {
					takeScreenshot(description);
					errorHandled.set(true);
					defaultFailureHandler.handle(throwable, matcher);
				});
				try {
					base.evaluate();
				} catch (Throwable t) {
					if (!errorHandled.get()) {
						takeScreenshot(description);
					}
					throw t;
				}
			}
		};
	}

	private void takeScreenshot(Description description) {
		String name = description.getTestClass().getSimpleName();
		ScreenCapture capture = Screenshot.capture();
		capture.setName(name);
		try {
			capture.process();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
