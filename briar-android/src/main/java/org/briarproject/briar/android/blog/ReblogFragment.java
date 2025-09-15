package org.briarproject.briar.android.blog;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.ScrollView;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.view.TextInputView;
import org.briarproject.briar.android.view.TextSendController;
import org.briarproject.briar.android.view.TextSendController.SendListener;
import org.briarproject.briar.android.view.TextSendController.SendState;
import org.briarproject.briar.android.widget.LinkDialogFragment;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

import static android.view.View.FOCUS_DOWN;
import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static java.util.Objects.requireNonNull;
import static org.briarproject.briar.android.activity.BriarActivity.GROUP_ID;
import static org.briarproject.briar.android.blog.BlogPostFragment.POST_ID;
import static org.briarproject.briar.android.view.TextSendController.SendState.SENT;
import static org.briarproject.briar.api.blog.BlogConstants.MAX_BLOG_POST_TEXT_LENGTH;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ReblogFragment extends BaseFragment implements SendListener {

	public static final String TAG = ReblogFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private BlogViewModel viewModel;
	private ViewHolder ui;
	private BlogPostItem item;

	static ReblogFragment newInstance(GroupId groupId, MessageId messageId) {
		ReblogFragment f = new ReblogFragment();

		Bundle args = new Bundle();
		args.putByteArray(GROUP_ID, groupId.getBytes());
		args.putByteArray(POST_ID, messageId.getBytes());
		f.setArguments(args);

		return f;
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(BlogViewModel.class);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {

		Bundle args = requireArguments();
		GroupId blogId =
				new GroupId(requireNonNull(args.getByteArray(GROUP_ID)));
		MessageId postId =
				new MessageId(requireNonNull(args.getByteArray(POST_ID)));

		View v = inflater.inflate(R.layout.fragment_reblog, container, false);
		ui = new ViewHolder(v);
		ui.post.setTransitionName(postId);
		TextSendController sendController =
				new TextSendController(ui.input, this, true);
		ui.input.setSendController(sendController);
		ui.input.setReady(false);
		ui.input.setMaxTextLength(MAX_BLOG_POST_TEXT_LENGTH);
		showProgressBar();

		viewModel.loadBlogPost(blogId, postId).observe(getViewLifecycleOwner(),
				result -> result.onError(this::handleException)
						.onSuccess(this::bindViewHolder)
		);

		return v;
	}

	private void bindViewHolder(BlogPostItem item) {
		this.item = item;

		hideProgressBar();

		ui.post.bindItem(this.item);
		ui.post.hideReblogButton();

		ui.input.setReady(true);
		ui.scrollView.post(() -> ui.scrollView.fullScroll(FOCUS_DOWN));
	}

	@Override
	public LiveData<SendState> onSendClick(@Nullable String text,
			List<AttachmentHeader> headers, long expectedAutoDeleteTimer) {
		ui.input.hideSoftKeyboard();
		viewModel.repeatPost(item, text);
		finish();
		return new MutableLiveData<>(SENT);
	}

	private void showProgressBar() {
		ui.progressBar.setVisibility(VISIBLE);
		ui.input.setVisibility(GONE);
	}

	private void hideProgressBar() {
		ui.progressBar.setVisibility(INVISIBLE);
		ui.input.setVisibility(VISIBLE);
	}

	private class ViewHolder implements OnBlogPostClickListener {

		private final ScrollView scrollView;
		private final ProgressBar progressBar;
		private final BlogPostViewHolder post;
		private final TextInputView input;

		private ViewHolder(View v) {
			scrollView = v.findViewById(R.id.scrollView);
			progressBar = v.findViewById(R.id.progressBar);
			post = new BlogPostViewHolder(v.findViewById(R.id.postLayout),
					true, this, false);
			input = v.findViewById(R.id.inputText);
		}

		@Override
		public void onBlogPostClick(BlogPostItem post) {
			// do nothing
		}

		@Override
		public void onAuthorClick(BlogPostItem post) {
			// probably don't want to allow author clicks here
		}

		@Override
		public void onLinkClick(String url) {
			LinkDialogFragment f = LinkDialogFragment.newInstance(url);
			f.show(getParentFragmentManager(), f.getUniqueTag());
		}
	}

}
