package org.briarproject.briar.android.conversation;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.Nullable;
import androidx.recyclerview.selection.ItemKeyProvider;

@NotNullByDefault
class ConversationItemKeyProvider extends ItemKeyProvider<String> {

	private final ConversationAdapter adapter;

	protected ConversationItemKeyProvider(ConversationAdapter adapter) {
		super(SCOPE_MAPPED);
		this.adapter = adapter;
	}

	@Nullable
	@Override
	public String getKey(int position) {
		return adapter.getItemKey(position);
	}

	@Override
	public int getPosition(String key) {
		return adapter.getPositionOfKey(key);
	}

}
