package org.briarproject.briar.android.conversation.glide;

import android.graphics.Bitmap;

import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;

public class BriarImageTransformation extends MultiTransformation<Bitmap> {

	public BriarImageTransformation(Radii r) {
		super(new CenterCrop(), new CustomCornersTransformation(r));
	}

}
