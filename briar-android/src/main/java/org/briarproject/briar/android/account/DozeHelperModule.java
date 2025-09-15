package org.briarproject.briar.android.account;

import org.briarproject.android.dontkillmelib.DozeHelper;
import org.briarproject.android.dontkillmelib.DozeHelperImpl;

import dagger.Module;
import dagger.Provides;

@Module
public class DozeHelperModule {

	@Provides
	DozeHelper provideDozeHelper() {
		return new DozeHelperImpl();
	}
}
