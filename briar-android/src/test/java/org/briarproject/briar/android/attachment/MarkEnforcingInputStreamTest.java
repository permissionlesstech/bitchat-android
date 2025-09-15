package org.briarproject.briar.android.attachment;

import com.bumptech.glide.util.MarkEnforcingInputStream;

import org.briarproject.bramble.test.BrambleTestCase;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class MarkEnforcingInputStreamTest extends BrambleTestCase {

	private final int readLimit = 4;
	private final byte[] bytes = {0x1, 0x2, 0x3, 0x4, 0x5, 0x6};

	@Test
	public void testPlainStreamReadsAllBytes() throws Exception {
		InputStream is = getStream();
		is.mark(readLimit);
		for (byte ignored : bytes) {
			assertNotEquals(-1, is.read());
		}
		assertEquals(0, is.available());
		is.close();
	}

	@Test
	public void testMarkEnforcingStreamReadsUntilMarkLimit() throws Exception {
		InputStream is = new MarkEnforcingInputStream(getStream());
		is.mark(readLimit);
		assertEquals(readLimit, is.available());
		for (int i = 0; i < bytes.length; i++) {
			if (i < readLimit) {
				assertEquals(readLimit - i, is.available());
				assertNotEquals(-1, is.read());
			} else {
				assertEquals(0, is.available());
				assertEquals(-1, is.read());
			}
		}
		assertEquals(0, is.available());
		is.close();
	}

	@Test
	public void testMarkEnforcingStreamCanBeReset() throws Exception {
		InputStream is = new MarkEnforcingInputStream(getStream());
		is.mark(readLimit);
		assertEquals(readLimit, is.available());
		for (int i = 0; i < readLimit; i++) {
			assertNotEquals(-1, is.read());
		}
		assertEquals(0, is.available());
		is.reset();
		is.mark(readLimit);
		assertEquals(readLimit, is.available());
		for (int i = 0; i < bytes.length; i++) {
			if (i < readLimit) {
				assertEquals(readLimit - i, is.available());
				assertNotEquals(-1, is.read());
			} else {
				assertEquals(0, is.available());
				assertEquals(-1, is.read());
			}
		}
		is.close();
	}

	private InputStream getStream() {
		return new BufferedInputStream(new ByteArrayInputStream(bytes));
	}

}
