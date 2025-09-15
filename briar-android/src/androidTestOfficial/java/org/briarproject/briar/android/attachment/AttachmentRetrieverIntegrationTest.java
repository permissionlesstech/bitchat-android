package org.briarproject.briar.android.attachment;

import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.android.attachment.media.ImageHelper;
import org.briarproject.briar.android.attachment.media.ImageSizeCalculator;
import org.briarproject.briar.api.attachment.Attachment;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.Random;

import javax.inject.Inject;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static androidx.test.InstrumentationRegistry.getContext;
import static org.briarproject.briar.android.attachment.AttachmentItem.State.AVAILABLE;
import static org.briarproject.briar.android.attachment.AttachmentItem.State.ERROR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class AttachmentRetrieverIntegrationTest {

	private final AttachmentDimensions dimensions = new AttachmentDimensions(
			100, 50, 200, 75, 300
	);
	private final GroupId groupId = new GroupId(getRandomId());
	private final MessageId msgId = new MessageId(getRandomId());

	@Inject
	ImageHelper imageHelper;
	@Inject
	ImageSizeCalculator imageSizeCalculator;

	private final AttachmentRetriever retriever;

	public AttachmentRetrieverIntegrationTest() {
		AbstractAttachmentRetrieverComponent component =
				DaggerAbstractAttachmentRetrieverComponent.builder().build();
		component.inject(this);

		retriever = new AttachmentRetrieverImpl(null, null, dimensions,
				imageHelper, imageSizeCalculator);
	}

	@Test
	public void testSmallJpegImage() throws Exception {
		AttachmentHeader h = new AttachmentHeader(groupId, msgId, "image/jpeg");
		InputStream is = getAssetInputStream("kitten_small.jpg");
		Attachment a = new Attachment(h, is);
		AttachmentItem item = retriever.createAttachmentItem(a, true);
		assertEquals(msgId, item.getMessageId());
		assertEquals(160, item.getWidth());
		assertEquals(240, item.getHeight());
		assertEquals(160, item.getThumbnailWidth());
		assertEquals(240, item.getThumbnailHeight());
		assertEquals("image/jpeg", item.getMimeType());
		assertJpgOrJpeg(item.getExtension());
		assertEquals(AVAILABLE, item.getState());
	}

	@Test
	public void testBigJpegImage() throws Exception {
		AttachmentHeader h = new AttachmentHeader(groupId, msgId, "image/jpeg");
		InputStream is = getAssetInputStream("kitten_original.jpg");
		Attachment a = new Attachment(h, is);
		AttachmentItem item = retriever.createAttachmentItem(a, true);
		assertEquals(msgId, item.getMessageId());
		assertEquals(1728, item.getWidth());
		assertEquals(2592, item.getHeight());
		assertEquals(dimensions.maxWidth, item.getThumbnailWidth());
		assertEquals(dimensions.maxHeight, item.getThumbnailHeight());
		assertEquals("image/jpeg", item.getMimeType());
		assertJpgOrJpeg(item.getExtension());
		assertEquals(AVAILABLE, item.getState());
	}

	@Test
	public void testSmallPngImage() throws Exception {
		AttachmentHeader h = new AttachmentHeader(groupId, msgId, "image/png");
		InputStream is = getAssetInputStream("kitten.png");
		Attachment a = new Attachment(h, is);
		AttachmentItem item = retriever.createAttachmentItem(a, true);
		assertEquals(msgId, item.getMessageId());
		assertEquals(737, item.getWidth());
		assertEquals(510, item.getHeight());
		assertEquals(dimensions.maxWidth, item.getThumbnailWidth());
		assertEquals(138, item.getThumbnailHeight());
		assertEquals("image/png", item.getMimeType());
		assertEquals("png", item.getExtension());
		assertEquals(AVAILABLE, item.getState());
	}

	@Test
	public void testUberGif() throws Exception {
		AttachmentHeader h = new AttachmentHeader(groupId, msgId, "image/gif");
		InputStream is = getAssetInputStream("uber.gif");
		Attachment a = new Attachment(h, is);
		AttachmentItem item = retriever.createAttachmentItem(a, true);
		assertEquals(1, item.getWidth());
		assertEquals(1, item.getHeight());
		assertEquals(dimensions.minHeight, item.getThumbnailWidth());
		assertEquals(dimensions.minHeight, item.getThumbnailHeight());
		assertEquals("image/gif", item.getMimeType());
		assertEquals("gif", item.getExtension());
		assertEquals(AVAILABLE, item.getState());
	}

	@Test
	public void testLottaPixels() throws Exception {
		AttachmentHeader h = new AttachmentHeader(groupId, msgId, "image/jpeg");
		InputStream is = getAssetInputStream("lottapixel.jpg");
		Attachment a = new Attachment(h, is);
		AttachmentItem item = retriever.createAttachmentItem(a, true);
		assertEquals(64250, item.getWidth());
		assertEquals(64250, item.getHeight());
		assertEquals(dimensions.maxWidth, item.getThumbnailWidth());
		assertEquals(dimensions.maxWidth, item.getThumbnailHeight());
		assertEquals("image/jpeg", item.getMimeType());
		assertJpgOrJpeg(item.getExtension());
		assertEquals(AVAILABLE, item.getState());
	}

	@Test
	public void testImageIoCrash() throws Exception {
		AttachmentHeader h = new AttachmentHeader(groupId, msgId, "image/png");
		InputStream is = getAssetInputStream("image_io_crash.png");
		Attachment a = new Attachment(h, is);
		AttachmentItem item = retriever.createAttachmentItem(a, true);
		assertEquals(1184, item.getWidth());
		assertEquals(448, item.getHeight());
		assertEquals(dimensions.maxWidth, item.getThumbnailWidth());
		assertEquals(dimensions.minHeight, item.getThumbnailHeight());
		assertEquals("image/png", item.getMimeType());
		assertEquals("png", item.getExtension());
		assertEquals(AVAILABLE, item.getState());
	}

	@Test
	public void testGimpCrash() throws Exception {
		AttachmentHeader h = new AttachmentHeader(groupId, msgId, "image/gif");
		InputStream is = getAssetInputStream("gimp_crash.gif");
		Attachment a = new Attachment(h, is);
		AttachmentItem item = retriever.createAttachmentItem(a, true);
		assertEquals(1, item.getWidth());
		assertEquals(1, item.getHeight());
		assertEquals(dimensions.minHeight, item.getThumbnailWidth());
		assertEquals(dimensions.minHeight, item.getThumbnailHeight());
		assertEquals("image/gif", item.getMimeType());
		assertEquals("gif", item.getExtension());
		assertEquals(AVAILABLE, item.getState());
	}

	@Test
	public void testOptiPngAfl() throws Exception {
		AttachmentHeader h = new AttachmentHeader(groupId, msgId, "image/gif");
		InputStream is = getAssetInputStream("opti_png_afl.gif");
		Attachment a = new Attachment(h, is);
		AttachmentItem item = retriever.createAttachmentItem(a, true);
		assertEquals(32, item.getWidth());
		assertEquals(32, item.getHeight());
		assertEquals(dimensions.minHeight, item.getThumbnailWidth());
		assertEquals(dimensions.minHeight, item.getThumbnailHeight());
		assertEquals("image/gif", item.getMimeType());
		assertEquals("gif", item.getExtension());
		assertEquals(AVAILABLE, item.getState());
	}

	@Test
	public void testLibrawError() throws Exception {
		AttachmentHeader h = new AttachmentHeader(groupId, msgId, "image/jpeg");
		InputStream is = getAssetInputStream("libraw_error.jpg");
		Attachment a = new Attachment(h, is);
		AttachmentItem item = retriever.createAttachmentItem(a, true);
		assertEquals(ERROR, item.getState());
	}

	@Test
	public void testSmallAnimatedGifMaxDimensions() throws Exception {
		AttachmentHeader h = new AttachmentHeader(groupId, msgId, "image/gif");
		InputStream is = getAssetInputStream("animated.gif");
		Attachment a = new Attachment(h, is);
		AttachmentItem item = retriever.createAttachmentItem(a, true);
		assertEquals(65535, item.getWidth());
		assertEquals(65535, item.getHeight());
		assertEquals(dimensions.maxWidth, item.getThumbnailWidth());
		assertEquals(dimensions.maxWidth, item.getThumbnailHeight());
		assertEquals("image/gif", item.getMimeType());
		assertEquals("gif", item.getExtension());
		assertEquals(AVAILABLE, item.getState());
	}

	@Test
	public void testSmallAnimatedGifHugeDimensions() throws Exception {
		AttachmentHeader h = new AttachmentHeader(groupId, msgId, "image/gif");
		InputStream is = getAssetInputStream("animated2.gif");
		Attachment a = new Attachment(h, is);
		AttachmentItem item = retriever.createAttachmentItem(a, true);
		assertEquals(10000, item.getWidth());
		assertEquals(10000, item.getHeight());
		assertEquals(dimensions.maxWidth, item.getThumbnailWidth());
		assertEquals(dimensions.maxWidth, item.getThumbnailHeight());
		assertEquals("image/gif", item.getMimeType());
		assertEquals("gif", item.getExtension());
		assertEquals(AVAILABLE, item.getState());
	}

	@Test
	public void testSmallGifLargeDimensions() throws Exception {
		AttachmentHeader h = new AttachmentHeader(groupId, msgId, "image/gif");
		InputStream is = getAssetInputStream("error_large.gif");
		Attachment a = new Attachment(h, is);
		AttachmentItem item = retriever.createAttachmentItem(a, true);
		assertEquals(16384, item.getWidth());
		assertEquals(16384, item.getHeight());
		assertEquals(dimensions.maxWidth, item.getThumbnailWidth());
		assertEquals(dimensions.maxWidth, item.getThumbnailHeight());
		assertEquals("image/gif", item.getMimeType());
		assertEquals("gif", item.getExtension());
		assertEquals(AVAILABLE, item.getState());
	}

	@Test
	public void testHighError() throws Exception {
		AttachmentHeader h = new AttachmentHeader(groupId, msgId, "image/jpeg");
		InputStream is = getAssetInputStream("error_high.jpg");
		Attachment a = new Attachment(h, is);
		AttachmentItem item = retriever.createAttachmentItem(a, true);
		assertEquals(1, item.getWidth());
		assertEquals(10000, item.getHeight());
		assertEquals(dimensions.minWidth, item.getThumbnailWidth());
		assertEquals(dimensions.maxHeight, item.getThumbnailHeight());
		assertEquals("image/jpeg", item.getMimeType());
		assertJpgOrJpeg(item.getExtension());
		assertEquals(AVAILABLE, item.getState());
	}

	@Test
	public void testWideError() throws Exception {
		AttachmentHeader h = new AttachmentHeader(groupId, msgId, "image/jpeg");
		InputStream is = getAssetInputStream("error_wide.jpg");
		Attachment a = new Attachment(h, is);
		AttachmentItem item = retriever.createAttachmentItem(a, true);
		assertEquals(1920, item.getWidth());
		assertEquals(1, item.getHeight());
		assertEquals(dimensions.maxWidth, item.getThumbnailWidth());
		assertEquals(dimensions.minHeight, item.getThumbnailHeight());
		assertEquals("image/jpeg", item.getMimeType());
		assertJpgOrJpeg(item.getExtension());
		assertEquals(AVAILABLE, item.getState());
	}

	private InputStream getAssetInputStream(String name) throws Exception {
		// pm.getResourcesForApplication(packageName).getAssets() did not work
		//noinspection deprecation
		return getContext().getAssets().open(name);
	}

	private void assertJpgOrJpeg(String extension) {
		assertTrue("jpg".equals(extension) || "jpeg".equals(extension));
	}

	public static byte[] getRandomBytes(int length) {
		byte[] b = new byte[length];
		new Random().nextBytes(b);
		return b;
	}

	public static byte[] getRandomId() {
		return getRandomBytes(UniqueId.LENGTH);
	}

}
