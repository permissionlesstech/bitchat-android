package org.briarproject.briar.android.attachment;

import android.net.Uri;

import java.io.IOException;

public class UnsupportedMimeTypeException extends IOException {

	private final String mimeType;
	private final Uri uri;

	public UnsupportedMimeTypeException(String mimeType, Uri uri) {
		this.mimeType = mimeType;
		this.uri = uri;
	}

	public String getMimeType() {
		return mimeType;
	}

	public Uri getUri() {
		return uri;
	}
}
