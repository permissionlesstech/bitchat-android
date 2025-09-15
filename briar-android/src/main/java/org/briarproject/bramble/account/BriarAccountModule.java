package org.briarproject.bramble.account;

import org.briarproject.bramble.api.account.AccountManager;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class BriarAccountModule {

	@Provides
	@Singleton
	AccountManager provideAccountManager(BriarAccountManager accountManager) {
		return accountManager;
	}
}
