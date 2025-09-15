package org.briarproject.briar.android.conversation.glide;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.data.DataFetcher;

import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.briar.api.attachment.Attachment;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.AttachmentReader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.Nullable;

import static com.bumptech.glide.load.DataSource.LOCAL;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.IoUtils.tryToClose;

@NotNullByDefault
class BriarDataFetcher implements DataFetcher<InputStream> {

	private final static Logger LOG =
			getLogger(BriarDataFetcher.class.getName());

	private final AttachmentReader attachmentReader;
	@DatabaseExecutor
	private final Executor dbExecutor;
	private final AttachmentHeader attachmentHeader;

	@Nullable
	private volatile InputStream inputStream;
	private volatile boolean cancel = false;

	@Inject
	BriarDataFetcher(AttachmentReader attachmentReader,
			@DatabaseExecutor Executor dbExecutor,
			AttachmentHeader attachmentHeader) {
		this.attachmentReader = attachmentReader;
		this.dbExecutor = dbExecutor;
		this.attachmentHeader = attachmentHeader;
	}

	@Override
	public void loadData(Priority priority,
			DataCallback<? super InputStream> callback) {
		dbExecutor.execute(() -> {
			if (cancel) return;
			try {
				Attachment a = attachmentReader.getAttachment(attachmentHeader);
				inputStream = a.getStream();
				callback.onDataReady(inputStream);
			} catch (DbException e) {
				callback.onLoadFailed(e);
			}
		});
	}

	@Override
	public void cleanup() {
		tryToClose(inputStream, LOG, WARNING);
	}

	@Override
	public void cancel() {
		cancel = true;
	}

	@Override
	public Class<InputStream> getDataClass() {
		return InputStream.class;
	}

	@Override
	public DataSource getDataSource() {
		return LOCAL;
	}

}
