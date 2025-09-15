package im.delight.android.identicons;

/**
 * Copyright 2014 www.delight.im <info@delight.im>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import static android.graphics.Paint.Style.FILL;

@UiThread
class Identicon {

	private static final int ROWS = 9, COLUMNS = 9;
	private static final int CENTER_COLUMN_INDEX = COLUMNS / 2 + COLUMNS % 2;

	private final byte[] input;
	private final Paint paint;
	private final int[][] colors;

	private int cellWidth, cellHeight;

	Identicon(@NonNull byte[] input) {
		if (input.length == 0) throw new IllegalArgumentException();
		this.input = input;

		paint = new Paint();
		paint.setStyle(FILL);
		paint.setAntiAlias(true);
		paint.setDither(true);

		colors = new int[ROWS][COLUMNS];
		int colorVisible = getForegroundColor();
		int colorInvisible = getBackgroundColor();

		for (int r = 0; r < ROWS; r++) {
			for (int c = 0; c < COLUMNS; c++) {
				if (isCellVisible(r, c)) colors[r][c] = colorVisible;
				else colors[r][c] = colorInvisible;
			}
		}
	}

	private byte getByte(int index) {
		return input[index % input.length];
	}

	private boolean isCellVisible(int row, int column) {
		return getByte(3 + row * CENTER_COLUMN_INDEX +
				getSymmetricColumnIndex(column)) >= 0;
	}

	private int getSymmetricColumnIndex(int index) {
		if (index < CENTER_COLUMN_INDEX) return index;
		else return COLUMNS - index - 1;
	}

	private int getForegroundColor() {
		int r = getByte(0) * 3 / 4 + 96;
		int g = getByte(1) * 3 / 4 + 96;
		int b = getByte(2) * 3 / 4 + 96;
		return Color.rgb(r, g, b);
	}

	private int getBackgroundColor() {
		// http://www.google.com/design/spec/style/color.html#color-themes
		return Color.rgb(0xFA, 0xFA, 0xFA);
	}

	void updateSize(int w, int h) {
		cellWidth = w / COLUMNS;
		cellHeight = h / ROWS;
	}

	void draw(Canvas canvas) {
		for (int r = 0; r < ROWS; r++) {
			for (int c = 0; c < COLUMNS; c++) {
				int x = cellWidth * c;
				int y = cellHeight * r;
				paint.setColor(colors[r][c]);
				canvas.drawRect(x, y + cellHeight, x + cellWidth, y, paint);
			}
		}
	}
}
