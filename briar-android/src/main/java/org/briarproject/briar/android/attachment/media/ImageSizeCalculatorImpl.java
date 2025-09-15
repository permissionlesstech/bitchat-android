package org.briarproject.briar.android.attachment.media;

import com.bumptech.glide.util.MarkEnforcingInputStream;

import org.briarproject.briar.android.attachment.media.ImageHelper.DecodeResult;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;

import androidx.exifinterface.media.ExifInterface;

import static androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270;
import static androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90;
import static androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE;
import static androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE;
import static androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH;
import static androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH;
import static androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logException;

@NotNullByDefault
class ImageSizeCalculatorImpl implements ImageSizeCalculator {

	private static final Logger LOG =
			getLogger(ImageSizeCalculatorImpl.class.getName());

	private static final int READ_LIMIT = 1024 * 8192;

	private final ImageHelper imageHelper;

	ImageSizeCalculatorImpl(ImageHelper imageHelper) {
		this.imageHelper = imageHelper;
	}

	@Override
	public Size getSize(InputStream is, String contentType) {
		Size size = new Size();
		is = new MarkEnforcingInputStream(is);
		is.mark(READ_LIMIT);
		if (contentType.equals("image/jpeg")) {
			try {
				// use exif to get size
				size = getSizeFromExif(is);
				is.reset();
			} catch (IOException e) {
				logException(LOG, WARNING, e);
			}
		}
		if (size.hasError()) {
			// need to mark again to re-add read limit
			is.mark(READ_LIMIT);
			try {
				// use BitmapFactory to get size
				size = getSizeFromBitmap(is);
				is.reset();
			} catch (IOException e) {
				logException(LOG, WARNING, e);
			}
		}
		return size;
	}

	/**
	 * Gets the size of a JPEG {@link InputStream} if EXIF info is available.
	 */
	private Size getSizeFromExif(InputStream is) throws IOException {
		ExifInterface exif = new ExifInterface(is);
		// these can return 0 independent of default value
		int width = exif.getAttributeInt(TAG_IMAGE_WIDTH, 0);
		int height = exif.getAttributeInt(TAG_IMAGE_LENGTH, 0);
		if (width == 0 || height == 0) return new Size();
		int orientation = exif.getAttributeInt(TAG_ORIENTATION, 0);
		if (orientation == ORIENTATION_ROTATE_90 ||
				orientation == ORIENTATION_ROTATE_270 ||
				orientation == ORIENTATION_TRANSVERSE ||
				orientation == ORIENTATION_TRANSPOSE) {
			//noinspection SuspiciousNameCombination
			return new Size(height, width, "image/jpeg");
		}
		return new Size(width, height, "image/jpeg");
	}

	/**
	 * Gets the size of any image {@link InputStream}.
	 */
	private Size getSizeFromBitmap(InputStream is) {
		DecodeResult result = imageHelper.decodeStream(is);
		if (result.width < 1 || result.height < 1) return new Size();
		return new Size(result.width, result.height, result.mimeType);
	}
}
