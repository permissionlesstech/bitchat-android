package org.briarproject.briar.android.mailbox;

import android.app.Application;

import com.google.zxing.Result;

import org.briarproject.bramble.api.Consumer;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.mailbox.MailboxManager;
import org.briarproject.bramble.api.mailbox.MailboxPairingState;
import org.briarproject.bramble.api.mailbox.MailboxPairingState.Paired;
import org.briarproject.bramble.api.mailbox.MailboxPairingTask;
import org.briarproject.bramble.api.mailbox.MailboxStatus;
import org.briarproject.bramble.api.mailbox.event.OwnMailboxConnectionStatusEvent;
import org.briarproject.bramble.api.plugin.Plugin;
import org.briarproject.bramble.api.plugin.PluginManager;
import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.event.TransportInactiveEvent;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.briar.android.mailbox.MailboxState.CameraError;
import org.briarproject.briar.android.mailbox.MailboxState.IsPaired;
import org.briarproject.briar.android.mailbox.MailboxState.NotSetup;
import org.briarproject.briar.android.mailbox.MailboxState.OfflineWhenPairing;
import org.briarproject.briar.android.mailbox.MailboxState.Pairing;
import org.briarproject.briar.android.mailbox.MailboxState.ScanningQrCode;
import org.briarproject.briar.android.mailbox.MailboxState.ShowDownload;
import org.briarproject.briar.android.mailbox.MailboxState.WasUnpaired;
import org.briarproject.briar.android.qrcode.QrCodeDecoder;
import org.briarproject.briar.android.viewmodel.DbViewModel;
import org.briarproject.briar.android.viewmodel.LiveEvent;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.briarproject.briar.api.android.AndroidNotificationManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.AnyThread;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static java.util.logging.Level.INFO;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE;

@NotNullByDefault
class MailboxViewModel extends DbViewModel
		implements QrCodeDecoder.ResultCallback, Consumer<MailboxPairingState>,
		EventListener {

	private static final Logger LOG =
			getLogger(MailboxViewModel.class.getName());

	private final EventBus eventBus;
	private final Executor ioExecutor;
	private final QrCodeDecoder qrCodeDecoder;
	private final PluginManager pluginManager;
	private final MailboxManager mailboxManager;
	private final AndroidNotificationManager notificationManager;

	private final MutableLiveEvent<MailboxState> pairingState =
			new MutableLiveEvent<>();
	private final MutableLiveData<MailboxStatus> status =
			new MutableLiveData<>();
	@Nullable
	private MailboxPairingTask pairingTask = null;

	@Inject
	MailboxViewModel(
			Application app,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			EventBus eventBus,
			@IoExecutor Executor ioExecutor,
			PluginManager pluginManager,
			MailboxManager mailboxManager,
			AndroidNotificationManager notificationManager) {
		super(app, dbExecutor, lifecycleManager, db, androidExecutor);
		this.eventBus = eventBus;
		this.ioExecutor = ioExecutor;
		this.pluginManager = pluginManager;
		this.mailboxManager = mailboxManager;
		this.notificationManager = notificationManager;
		qrCodeDecoder = new QrCodeDecoder(androidExecutor, ioExecutor, this);
		eventBus.addListener(this);
		checkIfSetup();
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		eventBus.removeListener(this);
		MailboxPairingTask task = pairingTask;
		if (task != null) {
			task.removeObserver(this);
			pairingTask = null;
		}
	}

	@UiThread
	private void checkIfSetup() {
		MailboxPairingTask task = mailboxManager.getCurrentPairingTask();
		if (task == null) {
			runOnDbThread(true, txn -> {
				boolean isPaired = mailboxManager.isPaired(txn);
				if (isPaired) {
					MailboxStatus mailboxStatus =
							mailboxManager.getMailboxStatus(txn);
					boolean isOnline = isTorActive();
					pairingState.postEvent(new IsPaired(isOnline));
					status.postValue(mailboxStatus);
				} else {
					pairingState.postEvent(new NotSetup());
				}
			}, this::handleException);
		} else {
			task.addObserver(this);
			pairingTask = task;
		}
	}

	@UiThread
	@Override
	public void eventOccurred(Event e) {
		if (e instanceof OwnMailboxConnectionStatusEvent) {
			MailboxStatus status =
					((OwnMailboxConnectionStatusEvent) e).getStatus();
			this.status.setValue(status);
		} else if (e instanceof TransportInactiveEvent) {
			TransportId id = ((TransportInactiveEvent) e).getTransportId();
			if (!TorConstants.ID.equals(id)) return;
			onTorInactive();
		}
	}

	@UiThread
	private void onTorInactive() {
		MailboxState lastState = pairingState.getLastValue();
		if (lastState instanceof IsPaired) {
			// we are already paired, so use IsPaired state
			pairingState.setEvent(new IsPaired(false));
		} else if (lastState instanceof Pairing) {
			Pairing p = (Pairing) lastState;
			// check that we not just finished pairing (showing success screen)
			if (!(p.pairingState instanceof Paired)) {
				pairingState.setEvent(new OfflineWhenPairing());
			}
			// else ignore offline event as user will be leaving UI flow anyway
		}
	}

	@UiThread
	void onScanButtonClicked() {
		if (isTorActive()) {
			pairingState.setEvent(new ScanningQrCode());
		} else {
			pairingState.setEvent(new OfflineWhenPairing());
		}
	}

	@UiThread
	void onCameraError() {
		pairingState.setEvent(new CameraError());
	}

	@Override
	@IoExecutor
	public void onQrCodeDecoded(Result result) {
		LOG.info("Got result from decoder");
		onQrCodePayloadReceived(result.getText());
	}

	@AnyThread
	private void onQrCodePayloadReceived(String qrCodePayload) {
		if (isTorActive()) {
			pairingTask = mailboxManager.startPairingTask(qrCodePayload);
			pairingTask.addObserver(this);
		} else {
			pairingState.postEvent(new OfflineWhenPairing());
		}
	}

	@UiThread
	@Override
	public void accept(MailboxPairingState mailboxPairingState) {
		if (LOG.isLoggable(INFO)) {
			LOG.info("New pairing state: " +
					mailboxPairingState.getClass().getSimpleName());
		}
		pairingState.setEvent(new Pairing(mailboxPairingState));
	}

	private boolean isTorActive() {
		Plugin plugin = pluginManager.getPlugin(TorConstants.ID);
		return plugin != null && plugin.getState() == ACTIVE;
	}

	@UiThread
	void showDownloadFragment() {
		pairingState.setEvent(new ShowDownload());
	}

	@UiThread
	QrCodeDecoder getQrCodeDecoder() {
		return qrCodeDecoder;
	}

	@UiThread
	void checkIfOnlineWhenPaired() {
		boolean isOnline = isTorActive();
		pairingState.setEvent(new IsPaired(isOnline));
	}

	LiveData<Boolean> checkConnection() {
		MutableLiveData<Boolean> liveData = new MutableLiveData<>();
		checkConnection(liveData::postValue);
		return liveData;
	}

	void checkConnectionFromWizard() {
		checkConnection(success -> {
			boolean isOnline = isTorActive();
			// make UI move back to status fragment by changing pairingState
			pairingState.postEvent(new IsPaired(isOnline));
		});
	}

	private void checkConnection(@IoExecutor Consumer<Boolean> consumer) {
		ioExecutor.execute(() -> {
			boolean success = mailboxManager.checkConnection();
			if (LOG.isLoggable(INFO)) {
				LOG.info("Got result from connection check: " + success);
			}
			consumer.accept(success);
		});
	}

	@UiThread
	void unlink() {
		ioExecutor.execute(() -> {
			try {
				boolean wasWiped = mailboxManager.unPair();
				pairingState.postEvent(new WasUnpaired(!wasWiped));
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void clearProblemNotification() {
		notificationManager.clearMailboxProblemNotification();
	}

	@UiThread
	LiveEvent<MailboxState> getPairingState() {
		return pairingState;
	}

	@UiThread
	LiveData<MailboxStatus> getStatus() {
		return status;
	}
}
