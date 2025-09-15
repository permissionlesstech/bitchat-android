package org.briarproject.briar.android.attachment.media;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static android.os.Build.VERSION.SDK_INT;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class ImageSizeCalculatorTest
		extends AbstractImageSizeCalculatorTest {

	@Override
	protected void inject(AbstractImageSizeCalculatorComponent component) {
		component.inject(this);
	}

	@Test
	public void testCalculateSizeKittenSmall() throws Exception {
		testCanCalculateSize("kitten_small.jpg", "image/jpeg", 160, 240);
	}

	@Test
	public void testCalculateSizeKittenSmallNoExif() throws Exception {
		testCanCalculateSize("kitten_small_noexif.jpg", "image/jpeg", 160, 240);
	}

	@Test
	public void testCalculateSizeSmallPng() throws Exception {
		testCanCalculateSize("red-100x100.png", "image/png", 100, 100);
	}

	@Test
	public void testCalculateSizeMediumPng() throws Exception {
		testCanCalculateSize("blue-500x500.png", "image/png", 500, 500);
	}

	@Test
	public void testCalculateSizeLargePng() throws Exception {
		testCanCalculateSize("green-1000x2000.png", "image/png", 1000, 2000);
	}

	@Test
	public void testCalculateSizeTransparentPng() throws Exception {
		testCanCalculateSize("transparent-100x100.png", "image/png", 100, 100);
	}

	@Test
	public void testCalculateSizeVeryHighJpg() throws Exception {
		testCanCalculateSize("error_high.jpg", "image/jpeg", 1, 10000);
	}

	@Test
	public void testCalculateSizeVeryWideJpg() throws Exception {
		testCanCalculateSize("error_wide.jpg", "image/jpeg", 1920, 1);
	}

	@Test
	public void testCalculateSizeAnimatedGif1() throws Exception {
		// TODO: Remove this assumption when we support large messages
		assumeTrue(SDK_INT >= 24);
		testCanCalculateSize("animated.gif", "image/gif", 65535, 65535);
	}

	@Test
	public void testCalculateSizeAnimatedGif2() throws Exception {
		// TODO: Remove this assumption when we support large messages
		assumeTrue(SDK_INT >= 24);
		testCanCalculateSize("animated2.gif", "image/gif", 10000, 10000);
	}

	@Test
	public void testCalculateSizeVeryLargeGif() throws Exception {
		// TODO: Remove this assumption when we support large messages
		assumeTrue(SDK_INT >= 24);
		testCanCalculateSize("error_large.gif", "image/gif", 16384, 16384);
	}
}
