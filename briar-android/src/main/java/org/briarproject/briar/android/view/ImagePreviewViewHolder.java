package org.briarproject.briar.android.view;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import org.briarproject.briar.R;
import org.briarproject.briar.android.conversation.glide.GlideApp;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static com.bumptech.glide.load.engine.DiskCacheStrategy.NONE;
import static com.bumptech.glide.load.resource.bitmap.DownsampleStrategy.FIT_CENTER;
import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

@NotNullByDefault
class ImagePreviewViewHolder extends ViewHolder {

	@DrawableRes
	private static final int ERROR_RES = R.drawable.ic_image_broken;

	private final ImageView imageView;
	private final ProgressBar progressBar;

	ImagePreviewViewHolder(View v) {
		super(v);
		this.imageView = v.findViewById(R.id.imageView);
		this.progressBar = v.findViewById(R.id.progressBar);
	}

	void bind(ImagePreviewItem item) {
		if (item.getItem() == null) {
			progressBar.setVisibility(VISIBLE);
			GlideApp.with(imageView)
					.clear(imageView);
		} else {
			GlideApp.with(imageView)
					.load(item.getItem().getHeader())
					.diskCacheStrategy(NONE)
					.error(ERROR_RES)
					.downsample(FIT_CENTER)
					.transition(withCrossFade())
					.addListener(new RequestListener<Drawable>() {
						@Override
						public boolean onLoadFailed(@Nullable GlideException e,
								Object model, Target<Drawable> target,
								boolean isFirstResource) {
							progressBar.setVisibility(INVISIBLE);
							return false;
						}

						@Override
						public boolean onResourceReady(Drawable resource,
								Object model, Target<Drawable> target,
								DataSource dataSource,
								boolean isFirstResource) {
							progressBar.setVisibility(INVISIBLE);
							return false;
						}
					})
					.into(imageView);
		}
	}

}
