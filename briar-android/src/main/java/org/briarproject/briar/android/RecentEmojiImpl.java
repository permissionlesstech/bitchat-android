package org.briarproject.briar.android;

import com.vanniktech.emoji.EmojiRange;
import com.vanniktech.emoji.EmojiUtils;
import com.vanniktech.emoji.RecentEmoji;
import com.vanniktech.emoji.emoji.Emoji;

import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.util.StringUtils;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import static java.util.logging.Level.WARNING;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.briar.android.settings.SettingsFragment.SETTINGS_NAMESPACE;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class RecentEmojiImpl implements RecentEmoji, OpenDatabaseHook {

	private static final Logger LOG =
			Logger.getLogger(RecentEmojiImpl.class.getName());

	private static final String EMOJI_LRU_PREFERENCE = "pref_emoji_recent2";
	private static final int EMOJI_LRU_SIZE = 50;

	// UI thread
	private final LinkedList<Emoji> recentlyUsed = new LinkedList<>();

	private final Executor dbExecutor;
	private final AndroidExecutor androidExecutor;
	private final SettingsManager settingsManager;

	@Inject
	RecentEmojiImpl(@DatabaseExecutor Executor dbExecutor,
			AndroidExecutor androidExecutor, SettingsManager settingsManager) {
		this.dbExecutor = dbExecutor;
		this.androidExecutor = androidExecutor;
		this.settingsManager = settingsManager;
	}

	@Override
	public Collection<Emoji> getRecentEmojis() {
		return new ArrayList<>(recentlyUsed);
	}

	@Override
	public void addEmoji(Emoji emoji) {
		recentlyUsed.remove(emoji);
		recentlyUsed.add(0, emoji);

		if (recentlyUsed.size() > EMOJI_LRU_SIZE) recentlyUsed.removeLast();
	}

	@Override
	public void persist() {
		if (!recentlyUsed.isEmpty()) save(serialize(recentlyUsed));
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		Settings settings =
				settingsManager.getSettings(txn, SETTINGS_NAMESPACE);
		String serialized = settings.get(EMOJI_LRU_PREFERENCE);
		if (serialized != null) {
			androidExecutor.runOnUiThread(() ->
					recentlyUsed.addAll(deserialize(serialized)));
		}
	}

	private String serialize(Collection<Emoji> emojis) {
		Collection<String> strings = new ArrayList<>(emojis.size());
		for (Emoji emoji : emojis) strings.add(emoji.getUnicode());
		return StringUtils.join(strings, "\t");
	}

	private Collection<Emoji> deserialize(String serialized) {
		Collection<EmojiRange> ranges = EmojiUtils.emojis(serialized);
		Collection<Emoji> result = new ArrayList<>(ranges.size());
		for (EmojiRange range : ranges) result.add(range.emoji);
		return result;
	}

	private void save(String serialized) {
		dbExecutor.execute(() -> {
			Settings settings = new Settings();
			settings.put(EMOJI_LRU_PREFERENCE, serialized);
			try {
				settingsManager.mergeSettings(settings, SETTINGS_NAMESPACE);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			}
		});
	}
}
