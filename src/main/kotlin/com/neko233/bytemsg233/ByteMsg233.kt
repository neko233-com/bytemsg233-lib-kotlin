package com.neko233.bytemsg233

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class WireType(val value: Int) {
    Varint(0), Fixed64(1), Bytes(2), Fixed32(5);
    companion object {
        fun fromValue(v: Int) = entries.firstOrNull { it.value == v } ?: Varint
    }
}

interface Resettable {
    fun reset()
}

class ByteMsgPool<T : Resettable>(private val factory: () -> T) {
    private val items = ArrayList<T>()

    fun acquire(): T {
        return if (items.isNotEmpty()) items.removeAt(items.lastIndex) else factory()
    }

    fun release(value: T) {
        value.reset()
        items.add(value)
    }
}

class ByteMsgWriter(initialCapacity: Int = 256) {
    private var buf = ByteArray(initialCapacity)
    private var pos = 0

    fun finish(): ByteArray = buf.copyOf(pos)
    fun reset() { pos = 0 }

    private fun ensure(additional: Int) {
        if (pos + additional > buf.size) {
            buf = buf.copyOf(maxOf(buf.size * 2, pos + additional))
        }
    }

    fun writeVarint(value: Long) {
        var v = value.toULong()
        while (v >= 0x80uL) {
            ensure(1)
            buf[pos++] = (v.toByte() or 0x80.toByte()).toByte()
            v = v shr 7
        }
        ensure(1)
        buf[pos++] = v.toByte()
    }

    fun writeHeader(tag: Int, wireType: WireType) {
        writeVarint((tag.toLong() shl 3) or wireType.value.toLong())
    }

    fun writeFixed32(value: Int) {
        ensure(4)
        buf[pos] = (value and 0xFF).toByte()
        buf[pos + 1] = ((value shr 8) and 0xFF).toByte()
        buf[pos + 2] = ((value shr 16) and 0xFF).toByte()
        buf[pos + 3] = ((value shr 24) and 0xFF).toByte()
        pos += 4
    }

    fun writeFixed64(value: Long) {
        ensure(8)
        for (i in 0 until 8) buf[pos + i] = ((value shr (i * 8)) and 0xFF).toByte()
        pos += 8
    }

    fun writeStringValue(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(bytes.size.toLong())
        ensure(bytes.size)
        System.arraycopy(bytes, 0, buf, pos, bytes.size)
        pos += bytes.size
    }

    fun writeString(tag: Int, value: String) {
        writeHeader(tag, WireType.Bytes)
        writeStringValue(value)
    }

    fun writeUintField(tag: Int, value: Long) {
        writeHeader(tag, WireType.Varint)
        writeVarint(value)
    }

    fun writeIntField(tag: Int, value: Int) {
        writeHeader(tag, WireType.Varint)
        writeVarint(value.toLong())
    }

    fun writeInt64Field(tag: Int, value: Long) {
        writeHeader(tag, WireType.Varint)
        writeVarint(zigzagEncode(value))
    }

    fun writeFloatField(tag: Int, value: Float) {
        writeHeader(tag, WireType.Fixed32)
        writeFixed32(value.toBits())
    }

    fun writeDoubleField(tag: Int, value: Double) {
        writeHeader(tag, WireType.Fixed64)
        writeFixed64(value.toBits())
    }

    fun writeBoolField(tag: Int, value: Boolean) {
        writeHeader(tag, WireType.Varint)
        writeVarint(if (value) 1L else 0L)
    }

    fun writeEnumField(tag: Int, value: Int) {
        writeHeader(tag, WireType.Varint)
        writeVarint(value.toLong())
    }

    fun writeBytesField(tag: Int, value: ByteArray) {
        writeHeader(tag, WireType.Bytes)
        writeVarint(value.size.toLong())
        ensure(value.size)
        System.arraycopy(value, 0, buf, pos, value.size)
        pos += value.size
    }

    fun <T> writeListField(tag: Int, items: List<T>, writeFn: (ByteMsgWriter, T) -> Unit) {
        writeHeader(tag, WireType.Bytes)
        val nested = ByteMsgWriter()
        nested.writeVarint(items.size.toLong())
        for (item in items) writeFn(nested, item)
        val nb = nested.finish()
        writeVarint(nb.size.toLong())
        ensure(nb.size)
        System.arraycopy(nb, 0, buf, pos, nb.size)
        pos += nb.size
    }

    fun writePackedVarints(tag: Int, values: LongArray) {
        writeHeader(tag, WireType.Bytes)
        val nested = ByteMsgWriter()
        nested.writeVarint(values.size.toLong())
        for (v in values) nested.writeVarint(v)
        val nb = nested.finish()
        writeVarint(nb.size.toLong())
        ensure(nb.size)
        System.arraycopy(nb, 0, buf, pos, nb.size)
        pos += nb.size
    }

    fun writeDeltaVarints(tag: Int, values: LongArray) {
        writeHeader(tag, WireType.Bytes)
        val nested = ByteMsgWriter()
        nested.writeVarint(values.size.toLong())
        if (values.isNotEmpty()) {
            var prev = values[0]
            nested.writeVarint(prev)
            for (i in 1 until values.size) {
                nested.writeVarint(zigzagEncode(values[i] - prev))
                prev = values[i]
            }
        }
        val nb = nested.finish()
        writeVarint(nb.size.toLong())
        ensure(nb.size)
        System.arraycopy(nb, 0, buf, pos, nb.size)
        pos += nb.size
    }

    fun writeBoolBitset(tag: Int, values: BooleanArray) {
        writeHeader(tag, WireType.Bytes)
        val nested = ByteMsgWriter()
        nested.writeVarint(values.size.toLong())
        var current: Byte = 0
        for (i in values.indices) {
            if (values[i]) current = (current.toInt() or (1 shl (i and 7))).toByte()
            if (i and 7 == 7) { nested.ensure(1); nested.buf[nested.pos++] = current; current = 0 }
        }
        if (values.size and 7 != 0) { nested.ensure(1); nested.buf[nested.pos++] = current }
        val nb = nested.finish()
        writeVarint(nb.size.toLong())
        ensure(nb.size)
        System.arraycopy(nb, 0, buf, pos, nb.size)
        pos += nb.size
    }

    fun writeStringList(tag: Int, values: List<String>) {
        writeHeader(tag, WireType.Bytes)
        val nested = ByteMsgWriter()
        nested.writeVarint(values.size.toLong())
        for (v in values) nested.writeStringValue(v)
        val nb = nested.finish()
        writeVarint(nb.size.toLong())
        ensure(nb.size)
        System.arraycopy(nb, 0, buf, pos, nb.size)
        pos += nb.size
    }

    companion object {
        fun zigzagEncode(value: Long): Long = (value shl 1) xor (value shr 63)
    }
}

class ByteMsgReader(private val data: ByteArray) {
    private var pos = 0

    val eof: Boolean get() = pos >= data.size
    val remaining: Int get() = data.size - pos

    fun readFieldHeader(): FieldHeader {
        val raw = readVarint()
        return FieldHeader((raw shr 3).toInt(), WireType.fromValue((raw and 0x7).toInt()))
    }

    fun readVarint(): Long {
        var value = 0L
        var shift = 0
        while (shift < 64) {
            if (pos >= data.size) return 0
            val b = data[pos++].toInt() and 0xFF
            value = value or ((b.toLong() and 0x7FL) shl shift)
            if (b < 0x80) return value
            shift += 7
        }
        return value
    }

    fun readVarintInt(): Int = readVarint().toInt()
    fun readVarintLong(): Long = readVarint()
    fun readVarintInt64(): Long = ByteMsgReaderZigzag.zigzagDecode(readVarint())

    fun readFixed32(): Int {
        if (data.size - pos < 4) return 0
        val v = (data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun readFixed64(): Long {
        if (data.size - pos < 8) return 0L
        var v = 0L
        for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (i * 8))
        pos += 8
        return v
    }

    fun readFloat(): Float = Float.fromBits(readFixed32())
    fun readDouble(): Double = Double.fromBits(readFixed64())
    fun readBool(): Boolean = readVarint() != 0L
    fun readEnum(): Int = readVarint().toInt()

    fun readString(): String {
        val len = readVarint().toInt()
        if (len > data.size - pos) return ""
        val s = String(data, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        if (len > data.size - pos) return ByteArray(0)
        val bytes = data.copyOfRange(pos, pos + len)
        pos += len
        return bytes
    }

    fun skipField(wireType: WireType) {
        when (wireType) {
            WireType.Varint -> readVarint()
            WireType.Fixed64 -> pos += 8
            WireType.Bytes -> { val n = readVarint().toInt(); pos += n }
            WireType.Fixed32 -> pos += 4
        }
    }

    fun <T> readList(readFn: (ByteMsgReader) -> T): List<T> {
        val count = readVarint().toInt()
        val len = readVarint().toInt()
        val end = pos + len
        val items = ArrayList<T>(count)
        for (i in 0 until count) items.add(readFn(this))
        pos = end
        return items
    }

    fun readPackedVarints(): LongArray {
        val count = readVarint().toInt()
        val len = readVarint().toInt()
        val end = pos + len
        val arr = LongArray(count)
        for (i in 0 until count) arr[i] = readVarint()
        pos = end
        return arr
    }

    fun readDeltaVarints(): LongArray {
        val count = readVarint().toInt()
        val len = readVarint().toInt()
        val end = pos + len
        val arr = LongArray(count)
        if (count > 0) {
            var value = readVarint()
            arr[0] = value
            for (i in 1 until count) {
                value = value + ByteMsgReaderZigzag.zigzagDecode(readVarint())
                arr[i] = value
            }
        }
        pos = end
        return arr
    }

    fun readBoolBitset(): BooleanArray {
        val count = readVarint().toInt()
        val len = readVarint().toInt()
        val end = pos + len
        val arr = BooleanArray(count)
        var i = 0
        while (i < count) {
            val current = data[pos++].toInt() and 0xFF
            val limit = minOf(8, count - i)
            for (b in 0 until limit) arr[i + b] = (current and (1 shl b)) != 0
            i += 8
        }
        pos = end
        return arr
    }

    fun readStringList(): List<String> {
        val count = readVarint().toInt()
        val len = readVarint().toInt()
        val end = pos + len
        val items = ArrayList<String>(count)
        for (i in 0 until count) items.add(readString())
        pos = end
        return items
    }
}

private object ByteMsgReaderZigzag {
    fun zigzagDecode(value: Long): Long = (value shr 1) xor -(value and 1)
}

data class FieldHeader(val tag: Int, val wireType: WireType)

data class ProtocolHello(var version: Long = 0, var minCompatible: Long = 0)

fun ByteMsgWriter.appendProtocolHello(hello: ProtocolHello) {
    writeHeader(1, WireType.Varint)
    writeVarint(hello.version)
    writeHeader(2, WireType.Varint)
    writeVarint(hello.minCompatible)
}

fun ByteMsgReader.readProtocolHello(): ProtocolHello {
    val hello = ProtocolHello()
    while (!eof) {
        val h = readFieldHeader()
        when (h.tag) {
            1 -> hello.version = readVarint()
            2 -> hello.minCompatible = readVarint()
            else -> skipField(h.wireType)
        }
    }
    return hello
}

fun checkProtocolHello(local: ProtocolHello, remote: ProtocolHello): Boolean {
    return remote.version >= local.minCompatible && local.version >= remote.minCompatible
}
