package org.briarproject.briar.android.attachment.media;

import android.graphics.BitmapFactory;
import android.webkit.MimeTypeMap;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import androidx.annotation.Nullable;

@Immutable
@NotNullByDefault
class ImageHelperImpl implements ImageHelper {

	@Inject
	ImageHelperImpl() {
	}

	@Override
	public DecodeResult decodeStream(InputStream is) {
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeStream(is, null, options);
		String mimeType = options.outMimeType;
		if (mimeType == null) mimeType = "";
		return new DecodeResult(options.outWidth, options.outHeight,
				mimeType);
	}

	@Nullable
	@Override
	public String getExtensionFromMimeType(String mimeType) {
		MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton();
		return mimeTypeMap.getExtensionFromMimeType(mimeType);
	}
}
