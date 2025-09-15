package org.briarproject.briar.android.conversation;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.View;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ItemDecoration;
import androidx.recyclerview.widget.RecyclerView.State;

import static org.briarproject.briar.android.conversation.ImageAdapter.isBottomRow;
import static org.briarproject.briar.android.conversation.ImageAdapter.isLeft;
import static org.briarproject.briar.android.conversation.ImageAdapter.isTopRow;
import static org.briarproject.briar.android.conversation.ImageAdapter.singleInRow;
import static org.briarproject.briar.android.util.UiUtils.isRtl;

@NotNullByDefault
class ImageItemDecoration extends ItemDecoration {

	private final int border;
	private final boolean isRtl;

	ImageItemDecoration(Context ctx) {
		Resources res = ctx.getResources();

		// for pixel perfection, add a pixel to the border if it has an odd size
		int b = res.getDimensionPixelSize(R.dimen.message_bubble_border);
		int realBorderSize = b % 2 == 0 ? b : b + 1;

		// we are applying half the border around the insides of each image
		// to prevent differently sized images looking slightly broken
		border = realBorderSize / 2;

		// find out if we are showing a RTL language
		isRtl = isRtl(ctx);
	}

	@Override
	public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
			State state) {
		if (state.getItemCount() == 1) return;
		int pos = parent.getChildAdapterPosition(view);
		int num = state.getItemCount();
		boolean start = isLeft(pos) ^ isRtl;
		outRect.top = isTopRow(pos) ? 0 : border;
		outRect.left = start ? 0 : border;
		outRect.right = start && !singleInRow(pos, num) ? border : 0;
		outRect.bottom = isBottomRow(pos, num) ? 0 : border;
	}

}
