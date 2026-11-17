package org.ledfxmobile.app.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends RGB frames with the DDP protocol (UDP port 4048). WLED switches into real-time mode
 * as soon as it receives DDP and returns to its own effects a few seconds after it stops.
 */
class DdpSender {
    companion object {
        const val PORT = 4048
        private const val HEADER = 10
        private const val MAX_PIXELS_PER_PACKET = 480 // 1440 bytes of RGB, fits one Wi-Fi frame
    }

    private val socket = DatagramSocket()
    private var sequence = 1
    private val packet = ByteArray(HEADER + MAX_PIXELS_PER_PACKET * 3)

    /** [pixels] are 0xRRGGBB. Splits long strips across several packets. */
    fun send(address: InetAddress, pixels: IntArray) {
        sequence = if (sequence >= 15) 1 else sequence + 1
        var offset = 0
        while (offset < pixels.size) {
            val count = minOf(MAX_PIXELS_PER_PACKET, pixels.size - offset)
            val last = offset + count >= pixels.size
            val byteOffset = offset * 3
            val length = count * 3

            packet[0] = (0x40 or if (last) 0x01 else 0x00).toByte() // version 1, push on last
            packet[1] = sequence.toByte()
            packet[2] = 0x0B          // RGB, 8 bits per channel
            packet[3] = 0x01          // default output device
            packet[4] = (byteOffset ushr 24).toByte()
            packet[5] = (byteOffset ushr 16).toByte()
            packet[6] = (byteOffset ushr 8).toByte()
            packet[7] = byteOffset.toByte()
            packet[8] = (length ushr 8).toByte()
            packet[9] = length.toByte()

            var p = HEADER
            for (i in offset until offset + count) {
                val c = pixels[i]
                packet[p++] = (c shr 16).toByte()
                packet[p++] = (c shr 8).toByte()
                packet[p++] = c.toByte()
            }
            socket.send(DatagramPacket(packet, HEADER + length, address, PORT))
            offset += count
        }
    }

    fun close() = socket.close()
}
