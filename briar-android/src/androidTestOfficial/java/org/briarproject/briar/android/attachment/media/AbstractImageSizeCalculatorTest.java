package org.briarproject.briar.android.attachment.media;

import android.content.res.AssetManager;

import java.io.IOException;
import java.io.InputStream;

import javax.inject.Inject;

import static androidx.test.InstrumentationRegistry.getContext;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public abstract class AbstractImageSizeCalculatorTest {

	@Inject
	ImageSizeCalculator imageSizeCalculator;

	public AbstractImageSizeCalculatorTest() {
		AbstractImageSizeCalculatorComponent component =
				DaggerAbstractImageSizeCalculatorComponent.builder().build();
		component.inject(this);
	}

	protected abstract void inject(
			AbstractImageSizeCalculatorComponent component);

	void testCanCalculateSize(String filename, String contentType, int width,
			int height) throws IOException {
		InputStream is = getAssetManager().open(filename);
		Size size = imageSizeCalculator.getSize(is, contentType);
		assertFalse(size.hasError());
		assertEquals(width, size.getWidth());
		assertEquals(height, size.getHeight());
	}

	void testCannotCalculateSize(String filename, String contentType)
			throws IOException {
		InputStream is = getAssetManager().open(filename);
		Size size = imageSizeCalculator.getSize(is, contentType);
		assertTrue(size.hasError());
	}

	static AssetManager getAssetManager() {
		// pm.getResourcesForApplication(packageName).getAssets() did not work
		//noinspection deprecation
		return getContext().getAssets();
	}

}
