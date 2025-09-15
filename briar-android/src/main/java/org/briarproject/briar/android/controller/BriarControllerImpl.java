package org.briarproject.briar.android.controller;

import android.app.Activity;
import android.content.Intent;
import android.os.IBinder;

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.briar.android.BriarApplication;
import org.briarproject.briar.android.BriarService;
import org.briarproject.briar.android.BriarService.BriarServiceConnection;
import org.briarproject.briar.android.controller.handler.ResultHandler;
import org.briarproject.briar.api.android.DozeWatchdog;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.CallSuper;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.android.dontkillmelib.DozeUtils.needsDozeWhitelisting;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.STARTING_SERVICES;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.briar.android.settings.SettingsFragment.SETTINGS_NAMESPACE;

@NotNullByDefault
public class BriarControllerImpl implements BriarController {

	private static final Logger LOG =
			getLogger(BriarControllerImpl.class.getName());

	public static final String DOZE_ASK_AGAIN = "dozeAskAgain";

	private final BriarServiceConnection serviceConnection;
	private final AccountManager accountManager;
	private final LifecycleManager lifecycleManager;
	private final Executor databaseExecutor;
	private final SettingsManager settingsManager;
	private final DozeWatchdog dozeWatchdog;
	private final AndroidWakeLockManager wakeLockManager;
	private final Activity activity;

	private boolean bound = false;

	@Inject
	BriarControllerImpl(BriarServiceConnection serviceConnection,
			AccountManager accountManager,
			LifecycleManager lifecycleManager,
			@DatabaseExecutor Executor databaseExecutor,
			SettingsManager settingsManager,
			DozeWatchdog dozeWatchdog,
			AndroidWakeLockManager wakeLockManager,
			Activity activity) {
		this.serviceConnection = serviceConnection;
		this.accountManager = accountManager;
		this.lifecycleManager = lifecycleManager;
		this.databaseExecutor = databaseExecutor;
		this.settingsManager = settingsManager;
		this.dozeWatchdog = dozeWatchdog;
		this.wakeLockManager = wakeLockManager;
		this.activity = activity;
	}

	@Override
	@CallSuper
	public void onActivityCreate(Activity activity) {
		if (accountManager.hasDatabaseKey()) startAndBindService();
	}

	@Override
	public void onActivityStart() {
	}

	@Override
	public void onActivityStop() {
	}

	@Override
	@CallSuper
	public void onActivityDestroy() {
		unbindService();
	}

	@Override
	public void startAndBindService() {
		activity.startService(new Intent(activity, BriarService.class));
		bound = activity.bindService(new Intent(activity, BriarService.class),
				serviceConnection, 0);
	}

	@Override
	public boolean accountSignedIn() {
		return accountManager.hasDatabaseKey() &&
				lifecycleManager.getLifecycleState().isAfter(STARTING_SERVICES);
	}

	@Override
	public void hasDozed(ResultHandler<Boolean> handler) {
		BriarApplication app = (BriarApplication) activity.getApplication();
		if (app.isInstrumentationTest() || !dozeWatchdog.getAndResetDozeFlag()
				|| !needsDozeWhitelisting(activity)) {
			handler.onResult(false);
			return;
		}
		databaseExecutor.execute(() -> {
			try {
				Settings settings =
						settingsManager.getSettings(SETTINGS_NAMESPACE);
				boolean ask = settings.getBoolean(DOZE_ASK_AGAIN, true);
				handler.onResult(ask);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			}
		});
	}

	@Override
	public void doNotAskAgainForDozeWhiteListing() {
		databaseExecutor.execute(() -> {
			try {
				Settings settings = new Settings();
				settings.putBoolean(DOZE_ASK_AGAIN, false);
				settingsManager.mergeSettings(settings, SETTINGS_NAMESPACE);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			}
		});
	}

	@Override
	public void signOut(ResultHandler<Void> handler, boolean deleteAccount) {
		wakeLockManager.executeWakefully(() -> {
			try {
				// Wait for the service to finish starting up
				IBinder binder = serviceConnection.waitForBinder();
				BriarService service =
						((BriarService.BriarBinder) binder).getService();
				service.waitForStartup();
				// Shut down the service and wait for it to shut down
				LOG.info("Shutting down service");
				service.shutdown(true);
				service.waitForShutdown();
			} catch (InterruptedException e) {
				LOG.warning("Interrupted while waiting for service");
			} finally {
				if (deleteAccount) accountManager.deleteAccount();
			}
			handler.onResult(null);
		}, "SignOut");
	}

	@Override
	public void deleteAccount() {
		accountManager.deleteAccount();
	}

	private void unbindService() {
		if (bound) activity.unbindService(serviceConnection);
	}

}
