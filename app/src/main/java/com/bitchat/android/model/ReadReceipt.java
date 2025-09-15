package com.bitchat.android.model;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Objects;

/**
 * Représente un accusé de lecture pour la compatibilité du transport de données.
 */
public final class ReadReceipt implements Parcelable {

    private final String originalMessageID;
    private final String readerPeerID;

    public ReadReceipt(String originalMessageID, String readerPeerID) {
        this.originalMessageID = originalMessageID;
        this.readerPeerID = readerPeerID;
    }

    // Getters
    public String getOriginalMessageID() { return originalMessageID; }
    public String getReaderPeerID() { return readerPeerID; }

    // --- Implémentation de Parcelable ---

    protected ReadReceipt(Parcel in) {
        originalMessageID = in.readString();
        readerPeerID = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(originalMessageID);
        dest.writeString(readerPeerID);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ReadReceipt> CREATOR = new Creator<ReadReceipt>() {
        @Override
        public ReadReceipt createFromParcel(Parcel in) {
            return new ReadReceipt(in);
        }
        @Override
        public ReadReceipt[] newArray(int size) {
            return new ReadReceipt[size];
        }
    };

    // --- equals et hashCode ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReadReceipt that = (ReadReceipt) o;
        return Objects.equals(originalMessageID, that.originalMessageID) &&
               Objects.equals(readerPeerID, that.readerPeerID);
    }

    @Override
    public int hashCode() {
        return Objects.hash(originalMessageID, readerPeerID);
    }
}
