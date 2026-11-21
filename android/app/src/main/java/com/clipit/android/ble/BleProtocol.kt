package com.clipit.android.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object BleProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("8D4F0001-3A5B-4C6D-9E8F-1B2C3D4E5F60")
    val CLIPBOARD_CHAR_UUID: UUID = UUID.fromString("8D4F0002-3A5B-4C6D-9E8F-1B2C3D4E5F60")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val HEADER_SIZE = 5

    data class ChunkPacket(
        val messageId: Byte,
        val totalChunks: Short,
        val chunkIndex: Short,
        val payload: ByteArray
    ) {
        fun serialize(): ByteArray {
            val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size).apply {
                order(ByteOrder.BIG_ENDIAN)
                put(messageId)
                putShort(totalChunks)
                putShort(chunkIndex)
                put(payload)
            }
            return buffer.array()
        }

        companion object {
            fun deserialize(data: ByteArray): ChunkPacket? {
                if (data.size < HEADER_SIZE) return null
                val buffer = ByteBuffer.wrap(data).apply {
                    order(ByteOrder.BIG_ENDIAN)
                }
                val msgId = buffer.get()
                val total = buffer.short
                val index = buffer.short
                val payload = ByteArray(data.size - HEADER_SIZE)
                buffer.get(payload)
                return ChunkPacket(msgId, total, index, payload)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ChunkPacket
            return messageId == other.messageId &&
                    totalChunks == other.totalChunks &&
                    chunkIndex == other.chunkIndex &&
                    payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = messageId.toInt()
            result = 31 * result + totalChunks
            result = 31 * result + chunkIndex
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    fun chunk(text: String, messageId: Byte, maxPacketSize: Int = 180): List<ByteArray> {
        val utf8Bytes = text.toByteArray(Charsets.UTF_8)
        val effectivePayloadSize = (maxPacketSize - HEADER_SIZE).coerceAtLeast(10)
        val totalCount = utf8Bytes.size

        if (totalCount == 0) {
            val single = ChunkPacket(messageId, 1, 0, ByteArray(0))
            return listOf(single.serialize())
        }

        val totalChunks = ((totalCount + effectivePayloadSize - 1) / effectivePayloadSize).toShort()
        val packets = ArrayList<ByteArray>(totalChunks.toInt())

        for (i in 0 until totalChunks) {
            val start = i * effectivePayloadSize
            val end = (start + effectivePayloadSize).coerceAtMost(totalCount)
            val chunkBytes = utf8Bytes.copyOfRange(start, end)
            val packet = ChunkPacket(messageId, totalChunks, i.toShort(), chunkBytes)
            packets.add(packet.serialize())
        }

        return packets
    }

    class Reassembler {
        private data class Buffer(
            val totalChunks: Short,
            val receivedChunks: MutableMap<Short, ByteArray> = HashMap(),
            var timestamp: Long = System.currentTimeMillis()
        )

        private val buffers = HashMap<Byte, Buffer>()
        private val timeoutMs = 6000L

        @Synchronized
        fun feed(packetData: ByteArray): String? {
            cleanupStaleBuffers()

            val packet = ChunkPacket.deserialize(packetData) ?: return null
            val buffer = buffers.getOrPut(packet.messageId) {
                Buffer(totalChunks = packet.totalChunks)
            }

            buffer.receivedChunks[packet.chunkIndex] = packet.payload
            buffer.timestamp = System.currentTimeMillis()

            if (buffer.receivedChunks.size == buffer.totalChunks.toInt()) {
                var fullSize = 0
                for (i in 0 until buffer.totalChunks) {
                    val part = buffer.receivedChunks[i.toShort()] ?: return null
                    fullSize += part.size
                }

                val fullBytes = ByteArray(fullSize)
                var offset = 0
                for (i in 0 until buffer.totalChunks) {
                    val part = buffer.receivedChunks[i.toShort()]!!
                    System.arraycopy(part, 0, fullBytes, offset, part.size)
                    offset += part.size
                }

                buffers.remove(packet.messageId)
                return String(fullBytes, Charsets.UTF_8)
            }

            return null
        }

        private fun cleanupStaleBuffers() {
            val now = System.currentTimeMillis()
            val iterator = buffers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.timestamp > timeoutMs) {
                    iterator.remove()
                }
            }
        }
    }
}
