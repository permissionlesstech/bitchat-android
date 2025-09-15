package org.briarproject.briar.android.conversation;

import org.briarproject.briar.android.view.BriarRecyclerViewScrollListener;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
class ConversationScrollListener extends
		BriarRecyclerViewScrollListener<ConversationAdapter, ConversationItem> {

	private final ConversationViewModel viewModel;

	protected ConversationScrollListener(ConversationAdapter adapter,
			ConversationViewModel viewModel) {
		super(adapter);
		this.viewModel = viewModel;
	}

	@Override
	protected void onItemVisible(ConversationItem item) {
		if (!item.isRead()) {
			viewModel.markMessageRead(item.getGroupId(), item.getId());
			item.markRead();
		}
	}

}
