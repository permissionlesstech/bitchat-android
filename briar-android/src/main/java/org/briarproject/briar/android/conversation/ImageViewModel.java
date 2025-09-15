package org.briarproject.briar.android.conversation;

import android.app.Application;
import android.content.ContentResolver;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;

import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.android.viewmodel.DbViewModel;
import org.briarproject.briar.android.viewmodel.LiveEvent;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.briarproject.briar.api.attachment.Attachment;
import org.briarproject.briar.api.attachment.AttachmentReader;
import org.briarproject.briar.api.messaging.event.AttachmentReceivedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import static android.media.MediaScannerConnection.scanFile;
import static android.os.Environment.DIRECTORY_PICTURES;
import static android.os.Environment.getExternalStoragePublicDirectory;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.IoUtils.copyAndClose;
import static org.briarproject.bramble.util.LogUtils.logException;

@NotNullByDefault
public class ImageViewModel extends DbViewModel implements EventListener {

	private static final Logger LOG = getLogger(ImageViewModel.class.getName());

	private final AttachmentReader attachmentReader;
	private final EventBus eventBus;
	@IoExecutor
	private final Executor ioExecutor;

	private boolean receivedAttachmentsInitialized = false;
	private final HashMap<MessageId, MutableLiveEvent<Boolean>>
			receivedAttachments = new HashMap<>();

	/**
	 * true means there was an error saving the image, false if image was saved.
	 */
	private final MutableLiveEvent<Boolean> saveState =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Boolean> imageClicked =
			new MutableLiveEvent<>();
	private int toolbarTop, toolbarBottom;

	@Inject
	ImageViewModel(Application application, AttachmentReader attachmentReader,
			EventBus eventBus, @DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			@IoExecutor Executor ioExecutor) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.attachmentReader = attachmentReader;
		this.eventBus = eventBus;
		this.ioExecutor = ioExecutor;

		eventBus.addListener(this);
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		eventBus.removeListener(this);
	}

	@UiThread
	@Override
	public void eventOccurred(Event e) {
		if (e instanceof AttachmentReceivedEvent) {
			MessageId id = ((AttachmentReceivedEvent) e).getMessageId();
			MutableLiveEvent<Boolean> oldEvent;
			if (receivedAttachmentsInitialized) {
				oldEvent = receivedAttachments.get(id);
				if (oldEvent != null) oldEvent.postEvent(true);
			} else {
				receivedAttachments.put(id, new MutableLiveEvent<>(true));
			}
		}
	}

	@UiThread
	void expectAttachments(List<AttachmentItem> attachments) {
		for (AttachmentItem item : attachments) {
			// no need to track items that are in a final state already
			if (item.getState().isFinal()) continue;
			// add new live events, if not already added by eventOccurred()
			MessageId id = item.getMessageId();
			if (!receivedAttachments.containsKey(id)) {
				receivedAttachments.put(id, new MutableLiveEvent<>());
			}
		}
		receivedAttachmentsInitialized = true;
	}

	/**
	 * Returns a LiveData for attachments in a non-final state.
	 * Note that you need to call {@link #expectAttachments(List)} first.
	 */
	@UiThread
	LiveEvent<Boolean> getOnAttachmentReceived(MessageId messageId) {
		return requireNonNull(receivedAttachments.get(messageId));
	}

	void clickImage() {
		imageClicked.setEvent(true);
	}

	/**
	 * A LiveEvent that is true if the image was clicked,
	 * false if it wasn't.
	 */
	LiveEvent<Boolean> getOnImageClicked() {
		return imageClicked;
	}

	void setToolbarPosition(int top, int bottom) {
		toolbarTop = top;
		toolbarBottom = bottom;
	}

	boolean isOverlappingToolbar(View screenView, Drawable drawable) {
		int width = drawable.getIntrinsicWidth();
		int height = drawable.getIntrinsicHeight();
		float widthPercentage = screenView.getWidth() / (float) width;
		float heightPercentage = screenView.getHeight() / (float) height;
		float scaleFactor = Math.min(widthPercentage, heightPercentage);
		int realWidth = (int) (width * scaleFactor);
		int realHeight = (int) (height * scaleFactor);
		// return if image doesn't use the full width,
		// because it will be moved to the right otherwise
		if (realWidth < screenView.getWidth()) return false;
		int drawableTop = (screenView.getHeight() - realHeight) / 2;
		return drawableTop < toolbarBottom && drawableTop != toolbarTop;
	}

	/**
	 * A LiveData that is true if there was an error
	 * and false if the image was saved.
	 */
	LiveEvent<Boolean> getSaveState() {
		return saveState;
	}

	/**
	 * Saves the attachment to a writeable {@link Uri}.
	 */
	@UiThread
	void saveImage(AttachmentItem attachment, @Nullable Uri uri) {
		if (uri == null) {
			onSaveImageError();
		} else {
			saveImage(attachment, () -> getOutputStream(uri), null);
		}
	}

	@UiThread
	void onSaveImageError() {
		saveState.setEvent(true);
	}

	/**
	 * Saves the attachment on external storage,
	 * assuming the permission was granted during install time.
	 */
	void saveImage(AttachmentItem attachment) {
		File file = getImageFile(attachment);
		saveImage(attachment, () -> getOutputStream(file), () -> scanFile(
				getApplication(), new String[] {file.toString()}, null, null));
	}

	private void saveImage(AttachmentItem attachment, OutputStreamProvider osp,
			@Nullable Runnable afterCopy) {
		runOnDbThread(() -> {
			try {
				Attachment a =
						attachmentReader.getAttachment(attachment.getHeader());
				copyImageFromDb(a, osp, afterCopy);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
				saveState.postEvent(true);
			}
		});
	}

	private void copyImageFromDb(Attachment a, OutputStreamProvider osp,
			@Nullable Runnable afterCopy) {
		ioExecutor.execute(() -> {
			try {
				InputStream is = a.getStream();
				OutputStream os = osp.getOutputStream();
				copyAndClose(is, os);
				if (afterCopy != null) afterCopy.run();
				saveState.postEvent(false);
			} catch (IOException e) {
				logException(LOG, WARNING, e);
				saveState.postEvent(true);
			}
		});
	}

	String getFileName() {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HHmmss", US);
		return sdf.format(new Date());
	}

	private File getImageFile(AttachmentItem attachment) {
		File path = getExternalStoragePublicDirectory(DIRECTORY_PICTURES);
		//noinspection ResultOfMethodCallIgnored
		path.mkdirs();
		String fileName = getFileName();
		String ext = "." + attachment.getExtension();
		File file = new File(path, fileName + ext);
		int i = 1;
		while (file.exists()) {
			file = new File(path, fileName + " (" + i + ")" + ext);
		}
		return file;
	}

	private OutputStream getOutputStream(File file) throws IOException {
		return new FileOutputStream(file);
	}

	private OutputStream getOutputStream(Uri uri) throws IOException {
		ContentResolver contentResolver = getApplication().getContentResolver();
		OutputStream os = contentResolver.openOutputStream(uri, "wt");
		if (os == null) throw new IOException();
		return os;
	}

	private interface OutputStreamProvider {
		OutputStream getOutputStream() throws IOException;
	}

}
