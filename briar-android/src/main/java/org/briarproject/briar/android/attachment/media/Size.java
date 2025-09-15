package org.briarproject.briar.android.attachment.media;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class Size {

	private final int width;
	private final int height;
	private final String mimeType;
	private final boolean error;

	public Size(int width, int height, String mimeType) {
		this.width = width;
		this.height = height;
		this.mimeType = mimeType;
		this.error = false;
	}

	public Size() {
		this.width = 0;
		this.height = 0;
		this.mimeType = "";
		this.error = true;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public String getMimeType() {
		return mimeType;
	}

	public boolean hasError() {
		return error;
	}

}
