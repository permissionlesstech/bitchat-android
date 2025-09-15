package org.briarproject.briar.android.attachment.media;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import androidx.test.filters.LargeTest;

import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.test.TestUtils.isOptionalTestEnabled;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;
import static org.junit.Assume.assumeTrue;

@LargeTest
@RunWith(Parameterized.class)
public class PngSuiteImageSizeCalculatorTest
		extends AbstractImageSizeCalculatorTest {

	private static final Logger LOG =
			getLogger(PngSuiteImageSizeCalculatorTest.class.getName());

	@Override
	protected void inject(AbstractImageSizeCalculatorComponent component) {
		component.inject(this);
	}

	@Parameters
	public static Iterable<String> data() throws IOException {
		List<String> data = new ArrayList<>();
		String[] files = requireNonNull(getAssetManager().list("PngSuite"));
		for (String filename : files)
			if (filename.endsWith(".png")) data.add(filename);
		return data;
	}

	private final String filename;

	public PngSuiteImageSizeCalculatorTest(String filename) {
		this.filename = filename;
	}

	//  some files have sizes other than 32x32
	private Map<String, Size> customSizes = new HashMap<>();

	{
		customSizes.put("cdfn2c08.png", new Size(8, 32, "image/png"));
		customSizes.put("cdhn2c08.png", new Size(32, 8, "image/png"));
		customSizes.put("cdsn2c08.png", new Size(8, 8, "image/png"));
		customSizes.put("PngSuite.png", new Size(256, 256, "image/png"));
	}

	@Test
	public void testPngSuiteCalculateSizes() throws Exception {
		assumeTrue(isOptionalTestEnabled(
				PngSuiteImageSizeCalculatorTest.class));
		LOG.info("Testing " + filename);
		if (filename.startsWith("x") && !filename.equals("xcsn0g01.png")) {
			testCannotCalculateSize("PngSuite/" + filename, "image/png");
		} else if (filename.startsWith("s")) {
			int size = Integer.parseInt(filename.substring(1, 3));
			testCanCalculateSize("PngSuite/" + filename, "image/png", size,
					size);
		} else {
			int width = 32;
			int height = 32;
			if (customSizes.containsKey(filename)) {
				Size size = customSizes.get(filename);
				width = size.getWidth();
				height = size.getHeight();
			}
			testCanCalculateSize("PngSuite/" + filename, "image/png", width,
					height);
		}
	}
}
