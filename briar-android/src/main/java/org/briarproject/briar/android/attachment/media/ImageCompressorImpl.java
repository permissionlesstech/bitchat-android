package org.briarproject.briar.android.attachment.media;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;

import javax.inject.Inject;

import static android.graphics.Bitmap.CompressFormat.JPEG;
import static android.graphics.BitmapFactory.decodeStream;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.IoUtils.tryToClose;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_IMAGE_SIZE;

class ImageCompressorImpl implements ImageCompressor {

	private static final Logger LOG =
			getLogger(ImageCompressorImpl.class.getName());

	private static final int MAX_ATTACHMENT_DIMENSION = 1000;

	private final ImageSizeCalculator imageSizeCalculator;

	@Inject
	ImageCompressorImpl(ImageSizeCalculator imageSizeCalculator) {
		this.imageSizeCalculator = imageSizeCalculator;
	}

	@Override
	public InputStream compressImage(InputStream is, String contentType)
			throws IOException {
		try {
			Bitmap bitmap =
					createBitmap(is, contentType, MAX_ATTACHMENT_DIMENSION);
			return compressImage(bitmap);
		} finally {
			tryToClose(is, LOG, WARNING);
		}
	}

	@Override
	public InputStream compressImage(Bitmap bitmap) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int quality = 100; quality >= 0; quality -= 10) {
			if (!bitmap.compress(JPEG, quality, out))
				throw new IOException();
			if (out.size() <= MAX_IMAGE_SIZE) {
				if (LOG.isLoggable(INFO)) {
					LOG.info("Compressed image to "
							+ out.size() + " bytes, quality " + quality);
				}
				return new ByteArrayInputStream(out.toByteArray());
			}
			out.reset();
		}
		throw new IOException();
	}

	private Bitmap createBitmap(InputStream is, String contentType, int maxSize)
			throws IOException {
		is = new BufferedInputStream(is);
		Size size = imageSizeCalculator.getSize(is, contentType);
		if (size.hasError()) throw new IOException();
		if (LOG.isLoggable(INFO))
			LOG.info("Original image size: " + size.getWidth() + "x" +
					size.getHeight());
		int dimension = Math.max(size.getWidth(), size.getHeight());
		int inSampleSize = 1;
		while (dimension > maxSize) {
			inSampleSize *= 2;
			dimension /= 2;
		}
		if (LOG.isLoggable(INFO))
			LOG.info("Scaling attachment by factor of " + inSampleSize);
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inSampleSize = inSampleSize;
		if (contentType.equals("image/png"))
			options.inPreferredConfig = Bitmap.Config.RGB_565;
		Bitmap bitmap = decodeStream(is, null, options);
		if (bitmap == null) throw new IOException();
		return bitmap;
	}

}
