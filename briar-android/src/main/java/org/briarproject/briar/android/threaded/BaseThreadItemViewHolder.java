package org.briarproject.briar.android.threaded;

import android.animation.Animator;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.text.util.Linkify;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.widget.TextView;

import org.briarproject.bramble.util.StringUtils;
import org.briarproject.briar.R;
import org.briarproject.briar.android.threaded.ThreadItemAdapter.ThreadItemListener;
import org.briarproject.briar.android.view.AuthorView;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.CallSuper;
import androidx.annotation.UiThread;
import androidx.recyclerview.widget.RecyclerView;

import static androidx.core.content.ContextCompat.getColor;
import static org.briarproject.briar.android.util.UiUtils.makeLinksClickable;

@UiThread
@NotNullByDefault
public abstract class BaseThreadItemViewHolder<I extends ThreadItem>
		extends RecyclerView.ViewHolder {

	private final static int ANIMATION_DURATION = 5000;

	protected final TextView textView;
	private final ViewGroup layout;
	private final AuthorView author;

	public BaseThreadItemViewHolder(View v) {
		super(v);

		layout = v.findViewById(R.id.layout);
		textView = v.findViewById(R.id.text);
		author = v.findViewById(R.id.author);
	}

	@CallSuper
	public void bind(I item, ThreadItemListener<I> listener) {
		textView.setText(StringUtils.trim(item.getText()));
		Linkify.addLinks(textView, Linkify.WEB_URLS);
		makeLinksClickable(textView, listener::onLinkClick);

		author.setAuthor(item.getAuthor(), item.getAuthorInfo());
		author.setDate(item.getTimestamp());

		if (item.isHighlighted()) {
			layout.setActivated(true);
		} else if (!item.isRead()) {
			layout.setActivated(true);
			animateFadeOut();
		} else {
			layout.setActivated(false);
		}
	}

	private void animateFadeOut() {
		setIsRecyclable(false);
		ValueAnimator anim = new ValueAnimator();
		int viewColor = getColor(getContext(), R.color.thread_item_highlight);
		anim.setIntValues(viewColor,
				getColor(getContext(), R.color.thread_item_background));
		anim.setEvaluator(new ArgbEvaluator());
		anim.setInterpolator(new AccelerateInterpolator());
		anim.addListener(new Animator.AnimatorListener() {
			@Override
			public void onAnimationStart(Animator animation) {
			}
			@Override
			public void onAnimationEnd(Animator animation) {
				layout.setBackgroundResource(
						R.drawable.list_item_thread_background);
				layout.setActivated(false);
				setIsRecyclable(true);
			}
			@Override
			public void onAnimationCancel(Animator animation) {
			}
			@Override
			public void onAnimationRepeat(Animator animation) {
			}
		});
		anim.addUpdateListener(valueAnimator -> layout.setBackgroundColor(
				(Integer) valueAnimator.getAnimatedValue()));
		anim.setDuration(ANIMATION_DURATION);
		anim.start();
	}

	protected Context getContext() {
		return textView.getContext();
	}

}
