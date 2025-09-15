package org.briarproject.briar.android.reporting;

import org.briarproject.nullsafety.NotNullByDefault;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

@NotThreadSafe
@NotNullByDefault
class ReportData {

	private final ArrayList<ReportItem> items = new ArrayList<>();

	ReportData add(ReportItem item) {
		items.add(item);
		return this;
	}

	List<ReportItem> getItems() {
		return items;
	}

	public JSONObject toJson(boolean includeReport) throws JSONException {
		JSONObject json = new JSONObject();
		for (ReportItem item : items) {
			// only include required items when report not added
			if (!includeReport && item.isOptional) continue;
			// only include what should be included
			if (!item.isIncluded) continue;
			json.put(item.name, item.info.toJson());
		}
		return json;
	}

	@NotNullByDefault
	static class ReportItem {
		final String name;
		@StringRes
		final int nameRes;
		final ReportInfo info;
		final boolean isOptional;
		boolean isIncluded = true;

		ReportItem(String name, @StringRes int nameRes, ReportInfo info) {
			this(name, nameRes, info, true);
		}

		ReportItem(String name, @StringRes int nameRes, String info) {
			this(name, nameRes, new SingleReportInfo(info), true);
		}

		ReportItem(String name, @StringRes int nameRes, ReportInfo info,
				boolean isOptional) {
			this.name = name;
			this.nameRes = nameRes;
			this.info = info;
			this.isOptional = isOptional;
		}
	}

	interface ReportInfo {
		Object toJson();
	}

	@Immutable
	@NotNullByDefault
	static class SingleReportInfo implements ReportInfo {
		private final String string;

		SingleReportInfo(String string) {
			this.string = string;
		}

		@Override
		public String toString() {
			return string;
		}

		@Override
		public Object toJson() {
			return string;
		}
	}

	@NotNullByDefault
	static class MultiReportInfo implements ReportInfo {
		private final Map<String, Object> map = new TreeMap<>();

		MultiReportInfo add(String key, @Nullable Object value) {
			map.put(key, value == null ? "null" : value);
			return this;
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			for (Map.Entry<String, Object> entry : map.entrySet()) {
				sb
						.append(entry.getKey())
						.append(": ")
						.append(entry.getValue())
						.append("\n");
			}
			return sb.toString();
		}

		@Override
		public Object toJson() {
			return new JSONObject(map);
		}
	}

}
