package com.bitchat.android.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.*
import android.util.Base64

//TODO DELETE WHEN MIGRATE TO KOTLINX DATE TIME

/**
 * Serializer for Date objects using milliseconds since epoch
 */
object DateSerializer : KSerializer<Date> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Date", PrimitiveKind.LONG)
    
    override fun serialize(encoder: Encoder, value: Date) {
        encoder.encodeLong(value.time)
    }
    
    override fun deserialize(decoder: Decoder): Date {
        return Date(decoder.decodeLong())
    }
}

/**
 * Serializer for ByteArray using Base64 encoding
 */
object ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ByteArray", PrimitiveKind.STRING)
    
    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.encodeToString(value, Base64.NO_WRAP))
    }
    
    override fun deserialize(decoder: Decoder): ByteArray {
        return Base64.decode(decoder.decodeString(), Base64.NO_WRAP)
    }
}

/**
 * Serializer for UByte values
 */
object UByteSerializer : KSerializer<UByte> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UByte", PrimitiveKind.INT)
    
    override fun serialize(encoder: Encoder, value: UByte) {
        encoder.encodeInt(value.toInt())
    }
    
    override fun deserialize(decoder: Decoder): UByte {
        return decoder.decodeInt().toUByte()
    }
}

/**
 * Serializer for ULong values
 */
object ULongSerializer : KSerializer<ULong> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ULong", PrimitiveKind.LONG)
    
    override fun serialize(encoder: Encoder, value: ULong) {
        encoder.encodeLong(value.toLong())
    }
    
    override fun deserialize(decoder: Decoder): ULong {
        return decoder.decodeLong().toULong()
    }
}