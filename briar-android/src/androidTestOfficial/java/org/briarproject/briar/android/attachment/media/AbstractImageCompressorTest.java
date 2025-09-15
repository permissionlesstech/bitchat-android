package org.briarproject.briar.android.attachment.media;

import android.content.res.AssetManager;

import java.io.IOException;
import java.io.InputStream;

import javax.inject.Inject;

import static androidx.test.InstrumentationRegistry.getContext;

public abstract class AbstractImageCompressorTest {

	@Inject
	ImageCompressor imageCompressor;

	public AbstractImageCompressorTest() {
		AbstractImageCompressorComponent component =
				DaggerAbstractImageCompressorComponent.builder().build();
		component.inject(this);
	}

	protected abstract void inject(
			AbstractImageCompressorComponent component);

	void testCompress(String filename, String contentType)
			throws IOException {
		InputStream is = getAssetManager().open(filename);
		imageCompressor.compressImage(is, contentType);
	}

	static AssetManager getAssetManager() {
		// pm.getResourcesForApplication(packageName).getAssets() did not work
		//noinspection deprecation
		return getContext().getAssets();
	}

}
