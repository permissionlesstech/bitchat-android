package org.briarproject.briar.android.attachment;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.bramble.test.ImmediateExecutor;
import org.briarproject.briar.android.attachment.media.ImageHelper;
import org.briarproject.briar.android.attachment.media.ImageSizeCalculator;
import org.briarproject.briar.android.attachment.media.Size;
import org.briarproject.briar.api.attachment.Attachment;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.AttachmentReader;
import org.jmock.Expectations;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.Executor;

import static org.briarproject.bramble.test.TestUtils.getRandomBytes;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.briar.android.attachment.AttachmentItem.State.AVAILABLE;
import static org.briarproject.briar.android.attachment.AttachmentItem.State.ERROR;
import static org.junit.Assert.assertEquals;

public class AttachmentRetrieverTest extends BrambleMockTestCase {

	private final AttachmentDimensions dimensions = new AttachmentDimensions(
			100, 50, 200, 75, 300
	);
	private final GroupId groupId = new GroupId(getRandomId());
	private final MessageId msgId = new MessageId(getRandomId());
	private final ImageHelper imageHelper = context.mock(ImageHelper.class);
	private final ImageSizeCalculator imageSizeCalculator;
	private final AttachmentRetriever retriever;

	public AttachmentRetrieverTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		AttachmentReader attachmentReader =
				context.mock(AttachmentReader.class);
		imageSizeCalculator = context.mock(ImageSizeCalculator.class);
		Executor dbExecutor = new ImmediateExecutor();
		retriever = new AttachmentRetrieverImpl(dbExecutor, attachmentReader,
				dimensions, imageHelper, imageSizeCalculator);
	}

	@Test
	public void testNoSize() {
		String mimeType = "image/jpeg";
		Attachment attachment = getAttachment(mimeType);

		context.checking(new Expectations() {{
			oneOf(imageHelper).getExtensionFromMimeType(mimeType);
			will(returnValue("jpg"));
		}});

		AttachmentItem item = retriever.createAttachmentItem(attachment, false);
		assertEquals(mimeType, item.getMimeType());
		assertEquals("jpg", item.getExtension());
		assertEquals(AVAILABLE, item.getState());
	}

	@Test
	public void testNoSizeWrongMimeTypeProducesError() {
		String mimeType = "application/octet-stream";
		Attachment attachment = getAttachment(mimeType);

		context.checking(new Expectations() {{
			oneOf(imageHelper).getExtensionFromMimeType(mimeType);
			will(returnValue(null));
		}});

		AttachmentItem item = retriever.createAttachmentItem(attachment, false);
		assertEquals(ERROR, item.getState());
	}

	@Test
	public void testSmallJpegImage() {
		String mimeType = "image/jpeg";
		Attachment attachment = getAttachment(mimeType);

		context.checking(new Expectations() {{
			oneOf(imageSizeCalculator).getSize(with(any(InputStream.class)),
					with(mimeType));
			will(returnValue(new Size(160, 240, mimeType)));
			oneOf(imageHelper).getExtensionFromMimeType(mimeType);
			will(returnValue("jpg"));
		}});

		AttachmentItem item = retriever.createAttachmentItem(attachment, true);
		assertEquals(msgId, item.getMessageId());
		assertEquals(160, item.getWidth());
		assertEquals(240, item.getHeight());
		assertEquals(160, item.getThumbnailWidth());
		assertEquals(240, item.getThumbnailHeight());
		assertEquals(mimeType, item.getMimeType());
		assertEquals("jpg", item.getExtension());
		assertEquals(AVAILABLE, item.getState());
	}

	@Test
	public void testBigJpegImage() {
		String mimeType = "image/jpeg";
		Attachment attachment = getAttachment(mimeType);

		context.checking(new Expectations() {{
			oneOf(imageSizeCalculator).getSize(with(any(InputStream.class)),
					with(mimeType));
			will(returnValue(new Size(1728, 2592, mimeType)));
			oneOf(imageHelper).getExtensionFromMimeType(mimeType);
			will(returnValue("jpg"));
		}});

		AttachmentItem item = retriever.createAttachmentItem(attachment, true);
		assertEquals(1728, item.getWidth());
		assertEquals(2592, item.getHeight());
		assertEquals(dimensions.maxWidth, item.getThumbnailWidth());
		assertEquals(dimensions.maxHeight, item.getThumbnailHeight());
		assertEquals(AVAILABLE, item.getState());
	}

	@Test
	public void testMalformedError() {
		String mimeType = "image/jpeg";
		Attachment attachment = getAttachment(mimeType);

		context.checking(new Expectations() {{
			oneOf(imageSizeCalculator).getSize(with(any(InputStream.class)),
					with(mimeType));
			will(returnValue(new Size()));
			oneOf(imageHelper).getExtensionFromMimeType("");
			will(returnValue(null));
		}});

		AttachmentItem item = retriever.createAttachmentItem(attachment, true);
		assertEquals(ERROR, item.getState());
	}

	private Attachment getAttachment(String contentType) {
		AttachmentHeader header =
				new AttachmentHeader(groupId, msgId, contentType);
		InputStream in = new ByteArrayInputStream(getRandomBytes(42));
		return new Attachment(header, in);
	}
}
