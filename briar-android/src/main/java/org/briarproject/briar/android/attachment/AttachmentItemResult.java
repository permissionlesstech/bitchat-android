package org.briarproject.briar.android.attachment;

import android.net.Uri;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class AttachmentItemResult {

	private final Uri uri;
	@Nullable
	private final AttachmentItem item;
	@Nullable
	private final String errorMsg;

	AttachmentItemResult(Uri uri, AttachmentItem item) {
		this.uri = uri;
		this.item = item;
		this.errorMsg = null;
	}

	AttachmentItemResult(Uri uri, @Nullable String errorMsg) {
		this.uri = uri;
		this.item = null;
		this.errorMsg = errorMsg;
	}

	public Uri getUri() {
		return uri;
	}

	@Nullable
	public AttachmentItem getItem() {
		return item;
	}

	public boolean hasError() {
		return item == null;
	}

	@Nullable
	public String getErrorMsg() {
		return errorMsg;
	}

}
