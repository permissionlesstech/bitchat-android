package org.briarproject.briar.android.threaded;

import android.view.View;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.briar.android.threaded.ThreadItemAdapter.ThreadItemListener;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Locale;

import androidx.annotation.UiThread;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

@UiThread
@NotNullByDefault
public class ThreadPostViewHolder<I extends ThreadItem>
		extends BaseThreadItemViewHolder<I> {

	private final TextView  lvlText;
	private final View[] lvls;
	private final View replyButton;

	private final static int[] nestedLineIds = {
			R.id.nested_line_1, R.id.nested_line_2, R.id.nested_line_3,
			R.id.nested_line_4, R.id.nested_line_5
	};

	public ThreadPostViewHolder(View v) {
		super(v);

		lvlText = v.findViewById(R.id.nested_line_text);
		lvls = new View[nestedLineIds.length];
		for (int i = 0; i < lvls.length; i++) {
			lvls[i] = v.findViewById(nestedLineIds[i]);
		}
		replyButton = v.findViewById(R.id.btn_reply);
	}

	@Override
	public void bind(I item, ThreadItemListener<I> listener) {
		super.bind(item, listener);

		for (int i = 0; i < lvls.length; i++) {
			lvls[i].setVisibility(i < item.getLevel() ? VISIBLE : GONE);
		}
		if (item.getLevel() > 5) {
			lvlText.setVisibility(VISIBLE);
			lvlText.setText(
					String.format(Locale.getDefault(), "%d", item.getLevel()));
		} else {
			lvlText.setVisibility(GONE);
		}

		replyButton.setOnClickListener(v -> listener.onReplyClick(item));
	}

}
