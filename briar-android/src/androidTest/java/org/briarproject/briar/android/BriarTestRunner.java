package org.briarproject.briar.android;

import android.app.Application;
import android.content.Context;

import androidx.test.runner.AndroidJUnitRunner;

public class BriarTestRunner extends AndroidJUnitRunner {

	@Override
	public Application newApplication(ClassLoader cl, String className,
			Context context)
			throws InstantiationException, IllegalAccessException,
			ClassNotFoundException {
		return super.newApplication(cl,
				BriarTestComponentApplication.class.getName(), context);
	}

}
