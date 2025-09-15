package org.briarproject.briar.android.hotspot;

import android.content.Context;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.annotation.Nullable;
import fi.iki.elonen.NanoHTTPD;

import static android.util.Xml.Encoding.UTF_8;
import static fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR;
import static fi.iki.elonen.NanoHTTPD.Response.Status.NOT_FOUND;
import static fi.iki.elonen.NanoHTTPD.Response.Status.OK;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.briar.BuildConfig.VERSION_NAME;
import static org.briarproject.briar.android.hotspot.HotspotViewModel.getApkFileName;

@NotNullByDefault
class WebServer extends NanoHTTPD {

	final static int PORT = 9999;

	private static final Logger LOG = getLogger(WebServer.class.getName());
	private static final String FILE_HTML = "hotspot.html";
	private static final Pattern REGEX_AGENT =
			Pattern.compile("Android ([0-9]+)");

	private final Context ctx;

	WebServer(Context ctx) {
		super(PORT);
		this.ctx = ctx;
	}

	@Override
	public void start() throws IOException {
		start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
	}

	@Override
	public Response serve(IHTTPSession session) {
		if (session.getUri().endsWith("favicon.ico")) {
			return newFixedLengthResponse(NOT_FOUND, MIME_PLAINTEXT,
					NOT_FOUND.getDescription());
		}
		if (session.getUri().endsWith(".apk")) {
			return serveApk();
		}
		Response res;
		try {
			String html = getHtml(session.getHeaders().get("user-agent"));
			res = newFixedLengthResponse(OK, MIME_HTML, html);
		} catch (Exception e) {
			logException(LOG, WARNING, e);
			res = newFixedLengthResponse(INTERNAL_ERROR, MIME_PLAINTEXT,
					ctx.getString(R.string.hotspot_error_web_server_serve));
		}
		return res;
	}

	private String getHtml(@Nullable String userAgent) throws Exception {
		Document doc;
		try (InputStream is = ctx.getAssets().open(FILE_HTML)) {
			doc = Jsoup.parse(is, UTF_8.name(), "");
		}
		String filename = getApkFileName();
		doc.select("#download_title").first()
				.text(ctx.getString(R.string.website_download_title_1,
						VERSION_NAME));
		doc.select("#download_intro").first()
				.text(ctx.getString(R.string.website_download_intro_1));
		doc.select(".button").first().attr("href", filename);
		doc.select("#download_button").first()
				.text(ctx.getString(R.string.website_download_button));
		doc.select("#download_outro").first()
				.text(ctx.getString(R.string.website_download_outro));
		doc.select("#troubleshooting_title").first()
				.text(ctx.getString(R.string.website_troubleshooting_title));
		doc.select("#troubleshooting_1").first()
				.text(ctx.getString(R.string.website_troubleshooting_1));
		doc.select("#troubleshooting_2").first()
				.text(getUnknownSourcesString(userAgent));
		return doc.outerHtml();
	}

	private String getUnknownSourcesString(@Nullable String userAgent) {
		boolean is8OrHigher = false;
		if (userAgent != null) {
			Matcher matcher = REGEX_AGENT.matcher(userAgent);
			if (matcher.find()) {
				int androidMajorVersion =
						Integer.parseInt(requireNonNull(matcher.group(1)));
				is8OrHigher = androidMajorVersion >= 8;
			}
		}
		return is8OrHigher ?
				ctx.getString(R.string.website_troubleshooting_2_new) :
				ctx.getString(R.string.website_troubleshooting_2_old);
	}

	private Response serveApk() {
		String mime = "application/vnd.android.package-archive";

		File file = new File(ctx.getPackageCodePath());
		long fileLen = file.length();

		Response res;
		try {
			FileInputStream fis = new FileInputStream(file);
			res = newFixedLengthResponse(OK, mime, fis, fileLen);
			res.addHeader("Content-Length", "" + fileLen);
		} catch (FileNotFoundException e) {
			logException(LOG, WARNING, e);
			res = newFixedLengthResponse(NOT_FOUND, MIME_PLAINTEXT,
					ctx.getString(R.string.hotspot_error_web_server_serve));
		}
		return res;
	}
}
