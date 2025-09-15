package com.bitchat.android.model;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Date;
import java.util.Objects;

/**
 * Représente l'état de livraison d'un message.
 * Ceci est l'équivalent Java de la "sealed class" Kotlin.
 * La classe de base est abstraite et chaque état est une sous-classe statique finale.
 */
public abstract class DeliveryStatus implements Parcelable {

    // Constructeur privé pour empêcher l'extension en dehors de ce fichier.
    private DeliveryStatus() {}

    /**
     * Retourne une chaîne de caractères lisible par l'utilisateur pour l'état de livraison.
     * @return Le texte à afficher.
     */
    public abstract String getDisplayText();

    // --- Sous-classes pour chaque état ---

    public static final class Sending extends DeliveryStatus {
        public Sending() {}
        @Override public String getDisplayText() { return "Envoi..."; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) { /* Pas de champs */ }
        public static final Creator<Sending> CREATOR = new Creator<Sending>() {
            @Override public Sending createFromParcel(Parcel in) { return new Sending(); }
            @Override public Sending[] newArray(int size) { return new Sending[size]; }
        };
    }

    public static final class Sent extends DeliveryStatus {
        public Sent() {}
        @Override public String getDisplayText() { return "Envoyé"; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) { /* Pas de champs */ }
        public static final Creator<Sent> CREATOR = new Creator<Sent>() {
            @Override public Sent createFromParcel(Parcel in) { return new Sent(); }
            @Override public Sent[] newArray(int size) { return new Sent[size]; }
        };
    }

    public static final class Delivered extends DeliveryStatus {
        public final String to;
        public final Date at;
        public Delivered(String to, Date at) { this.to = to; this.at = at; }
        @Override public String getDisplayText() { return "Distribué à " + this.to; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) { dest.writeString(to); dest.writeLong(at.getTime()); }
        public static final Creator<Delivered> CREATOR = new Creator<Delivered>() {
            @Override public Delivered createFromParcel(Parcel in) { return new Delivered(in.readString(), new Date(in.readLong())); }
            @Override public Delivered[] newArray(int size) { return new Delivered[size]; }
        };
        @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; Delivered delivered = (Delivered) o; return Objects.equals(to, delivered.to) && Objects.equals(at, delivered.at); }
        @Override public int hashCode() { return Objects.hash(to, at); }
    }

    public static final class Read extends DeliveryStatus {
        public final String by;
        public final Date at;
        public Read(String by, Date at) { this.by = by; this.at = at; }
        @Override public String getDisplayText() { return "Lu par " + this.by; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) { dest.writeString(by); dest.writeLong(at.getTime()); }
        public static final Creator<Read> CREATOR = new Creator<Read>() {
            @Override public Read createFromParcel(Parcel in) { return new Read(in.readString(), new Date(in.readLong())); }
            @Override public Read[] newArray(int size) { return new Read[size]; }
        };
        @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; Read read = (Read) o; return Objects.equals(by, read.by) && Objects.equals(at, read.at); }
        @Override public int hashCode() { return Objects.hash(by, at); }
    }

    public static final class Failed extends DeliveryStatus {
        public final String reason;
        public Failed(String reason) { this.reason = reason; }
        @Override public String getDisplayText() { return "Échec : " + this.reason; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) { dest.writeString(reason); }
        public static final Creator<Failed> CREATOR = new Creator<Failed>() {
            @Override public Failed createFromParcel(Parcel in) { return new Failed(in.readString()); }
            @Override public Failed[] newArray(int size) { return new Failed[size]; }
        };
        @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; Failed failed = (Failed) o; return Objects.equals(reason, failed.reason); }
        @Override public int hashCode() { return Objects.hash(reason); }
    }

    public static final class PartiallyDelivered extends DeliveryStatus {
        public final int reached;
        public final int total;
        public PartiallyDelivered(int reached, int total) { this.reached = reached; this.total = total; }
        @Override public String getDisplayText() { return "Distribué à " + this.reached + "/" + this.total; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) { dest.writeInt(reached); dest.writeInt(total); }
        public static final Creator<PartiallyDelivered> CREATOR = new Creator<PartiallyDelivered>() {
            @Override public PartiallyDelivered createFromParcel(Parcel in) { return new PartiallyDelivered(in.readInt(), in.readInt()); }
            @Override public PartiallyDelivered[] newArray(int size) { return new PartiallyDelivered[size]; }
        };
        @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; PartiallyDelivered that = (PartiallyDelivered) o; return reached == that.reached && total == that.total; }
        @Override public int hashCode() { return Objects.hash(reached, total); }
    }
}
