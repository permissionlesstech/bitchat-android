package org.briarproject.briar.android.conversation.glide;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.Nullable;

@NotNullByDefault
public class Radii {

	public final int topLeft, topRight, bottomLeft, bottomRight;

	public Radii(int topLeft, int topRight, int bottomLeft, int bottomRight) {
		this.topLeft = topLeft;
		this.topRight = topRight;
		this.bottomLeft = bottomLeft;
		this.bottomRight = bottomRight;
	}

	@Override
	public boolean equals(@Nullable Object o) {
		return o instanceof Radii &&
				topLeft == ((Radii) o).topLeft &&
				topRight == ((Radii) o).topRight &&
				bottomLeft == ((Radii) o).bottomLeft &&
				bottomRight == ((Radii) o).bottomRight;
	}

	@Override
	public int hashCode() {
		return topLeft << 24 ^ topRight << 16 ^ bottomLeft << 8 ^ bottomRight;
	}

	@Override
	public String toString() {
		return "Radii(topLeft=" + topLeft +
				",topRight=" + topRight +
				",bottomLeft=" + bottomLeft +
				",bottomRight=" + bottomRight;
	}

}
