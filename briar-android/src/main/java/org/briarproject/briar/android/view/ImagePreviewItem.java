package org.briarproject.briar.android.view;

import android.net.Uri;

import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import androidx.annotation.Nullable;

@NotNullByDefault
class ImagePreviewItem {

	private final Uri uri;
	@Nullable
	private AttachmentItem item;

	ImagePreviewItem(Uri uri) {
		this.uri = uri;
		this.item = null;
	}

	static List<ImagePreviewItem> fromUris(Collection<Uri> uris) {
		List<ImagePreviewItem> items = new ArrayList<>(uris.size());
		for (Uri uri : uris) {
			items.add(new ImagePreviewItem(uri));
		}
		return items;
	}

	public void setItem(AttachmentItem item) {
		this.item = item;
	}

	@Nullable
	public AttachmentItem getItem() {
		return item;
	}

	@Override
	public boolean equals(@Nullable Object o) {
		return o instanceof ImagePreviewItem &&
				uri.equals(((ImagePreviewItem) o).uri);
	}

	@Override
	public int hashCode() {
		return uri.hashCode();
	}

}
