package org.briarproject.briar.android.attachment;

import android.os.Parcel;
import android.os.Parcelable;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import androidx.annotation.Nullable;

import static java.lang.System.arraycopy;
import static java.util.Objects.requireNonNull;
import static org.briarproject.bramble.util.StringUtils.toHexString;
import static org.briarproject.briar.android.attachment.AttachmentItem.State.LOADING;
import static org.briarproject.briar.android.attachment.AttachmentItem.State.MISSING;

@Immutable
@NotNullByDefault
public class AttachmentItem implements Parcelable {

	public enum State {
		LOADING, MISSING, AVAILABLE, ERROR;

		public boolean isFinal() {
			return this == AVAILABLE || this == ERROR;
		}
	}

	private final AttachmentHeader header;
	private final int width, height;
	private final String extension;
	private final int thumbnailWidth, thumbnailHeight;
	private final State state;

	public static final Creator<AttachmentItem> CREATOR =
			new Creator<AttachmentItem>() {
				@Override
				public AttachmentItem createFromParcel(Parcel in) {
					return new AttachmentItem(in);
				}

				@Override
				public AttachmentItem[] newArray(int size) {
					return new AttachmentItem[size];
				}
			};

	AttachmentItem(AttachmentHeader header, int width, int height,
			String extension, int thumbnailWidth, int thumbnailHeight,
			State state) {
		this.header = header;
		this.width = width;
		this.height = height;
		this.extension = extension;
		this.thumbnailWidth = thumbnailWidth;
		this.thumbnailHeight = thumbnailHeight;
		this.state = state;
	}

	/**
	 * Use only for {@link State MISSING} or {@link State LOADING} items.
	 */
	AttachmentItem(AttachmentHeader header, int width, int height,
			State state) {
		this(header, width, height, "", width, height, state);
		if (state != MISSING && state != LOADING)
			throw new IllegalArgumentException();
	}

	/**
	 * Use when the item does not need a size.
	 */
	AttachmentItem(AttachmentHeader header, String extension, State state) {
		this(header, 0, 0, extension, 0, 0, state);
	}

	protected AttachmentItem(Parcel in) {
		byte[] groupIdByte = new byte[GroupId.LENGTH];
		in.readByteArray(groupIdByte);
		GroupId groupId = new GroupId(groupIdByte);
		byte[] messageIdByte = new byte[MessageId.LENGTH];
		in.readByteArray(messageIdByte);
		MessageId messageId = new MessageId(messageIdByte);
		width = in.readInt();
		height = in.readInt();
		String mimeType = requireNonNull(in.readString());
		extension = requireNonNull(in.readString());
		thumbnailWidth = in.readInt();
		thumbnailHeight = in.readInt();
		state = State.valueOf(requireNonNull(in.readString()));
		header = new AttachmentHeader(groupId, messageId, mimeType);
	}

	public AttachmentHeader getHeader() {
		return header;
	}

	public MessageId getMessageId() {
		return header.getMessageId();
	}

	int getWidth() {
		return width;
	}

	int getHeight() {
		return height;
	}

	public String getMimeType() {
		return header.getContentType();
	}

	public String getExtension() {
		return extension;
	}

	public int getThumbnailWidth() {
		return thumbnailWidth;
	}

	public int getThumbnailHeight() {
		return thumbnailHeight;
	}

	public State getState() {
		return state;
	}

	public String getTransitionName(MessageId conversationItemId) {
		int len = MessageId.LENGTH;
		byte[] instanceId = new byte[len * 2];
		arraycopy(header.getMessageId().getBytes(), 0, instanceId, 0, len);
		arraycopy(conversationItemId.getBytes(), 0, instanceId, len, len);
		return toHexString(instanceId);
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeByteArray(header.getGroupId().getBytes());
		dest.writeByteArray(header.getMessageId().getBytes());
		dest.writeInt(width);
		dest.writeInt(height);
		dest.writeString(header.getContentType());
		dest.writeString(extension);
		dest.writeInt(thumbnailWidth);
		dest.writeInt(thumbnailHeight);
		dest.writeString(state.name());
	}

	/**
	 * This is used to identity if two items are the same,
	 * irrespective of their state or size.
	 */
	@Override
	public boolean equals(@Nullable Object o) {
		return o instanceof AttachmentItem &&
				header.getMessageId().equals(
						((AttachmentItem) o).header.getMessageId()
				);
	}

	@Override
	public int hashCode() {
		return header.getMessageId().hashCode();
	}
}
