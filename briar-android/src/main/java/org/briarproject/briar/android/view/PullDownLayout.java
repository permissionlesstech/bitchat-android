/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 XiNGRZ <xxx@oxo.ooo>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.briarproject.briar.android.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.customview.widget.ViewDragHelper;

@NotNullByDefault
public class PullDownLayout extends FrameLayout {

	private final ViewDragHelper dragger;

	private final int minimumFlingVelocity;

	@Nullable
	private Callback callback;

	public PullDownLayout(Context context) {
		this(context, null);
	}

	public PullDownLayout(Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public PullDownLayout(Context context, @Nullable AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		dragger = ViewDragHelper.create(this, 1f / 8f, new ViewDragCallback());
		minimumFlingVelocity =
				ViewConfiguration.get(context).getScaledMinimumFlingVelocity();
	}

	public void setCallback(@Nullable Callback callback) {
		this.callback = callback;
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent ev) {
		return dragger.shouldInterceptTouchEvent(ev);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		dragger.processTouchEvent(event);
		return true;
	}

	@Override
	public void computeScroll() {
		if (dragger.continueSettling(true)) {
			ViewCompat.postInvalidateOnAnimation(this);
		}
	}

	public interface Callback {

		void onPullStart();

		void onPull(float progress);

		void onPullCancel();

		void onPullComplete();

	}

	private class ViewDragCallback extends ViewDragHelper.Callback {

		@Override
		public boolean tryCaptureView(View child, int pointerId) {
			return true;
		}

		@Override
		public int clampViewPositionHorizontal(View child, int left, int dx) {
			return 0;
		}

		@Override
		public int clampViewPositionVertical(View child, int top, int dy) {
			return Math.max(0, top);
		}

		@Override
		public int getViewHorizontalDragRange(View child) {
			return 0;
		}

		@Override
		public int getViewVerticalDragRange(View child) {
			return getHeight();
		}

		@Override
		public void onViewCaptured(View capturedChild, int activePointerId) {
			if (callback != null) {
				callback.onPullStart();
			}
		}

		@Override
		public void onViewPositionChanged(View changedView, int left, int top,
				int dx, int dy) {
			if (callback != null) {
				callback.onPull((float) top / (float) getHeight());
			}
		}

		@Override
		public void onViewReleased(View releasedChild, float xvel, float yvel) {
			int slop = yvel > minimumFlingVelocity ? getHeight() / 6 :
					getHeight() / 3;
			if (releasedChild.getTop() > slop) {
				if (callback != null) {
					callback.onPullComplete();
				}
			} else {
				if (callback != null) {
					callback.onPullCancel();
				}

				dragger.settleCapturedViewAt(0, 0);
				invalidate();
			}
		}

	}

}
