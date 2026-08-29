package com.audioshare.usbcompanion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException

class WireProtocolTest {
    @Test
    fun frameRoundTripsBinaryPayload() {
        val payload = byteArrayOf(0x00, 0x0A, 0x0D, 0x1A, 0xFF.toByte())
        val output = ByteArrayOutputStream()
        WireProtocol.writeFrame(output, WireProtocol.Frame(WireProtocol.Type.PCM, 12, payload))

        val result = WireProtocol.readFrame(ByteArrayInputStream(output.toByteArray()))!!

        assertEquals(WireProtocol.Type.PCM, result.type)
        assertEquals(12, result.sequence)
        assertArrayEquals(payload, result.payload)
    }

    @Test
    fun cleanEofReturnsNull() {
        assertNull(WireProtocol.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test(expected = ProtocolException::class)
    fun rejectsInvalidMagic() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).apply {
            writeInt(0x01020304)
            writeShort(WireProtocol.VERSION)
            writeShort(WireProtocol.Type.PING.id)
            writeInt(0)
            writeInt(1)
        }
        WireProtocol.readFrame(ByteArrayInputStream(output.toByteArray()))
    }

    @Test(expected = ProtocolException::class)
    fun rejectsOversizedPcmBeforeAllocation() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).apply {
            writeInt(WireProtocol.MAGIC)
            writeShort(WireProtocol.VERSION)
            writeShort(WireProtocol.Type.PCM.id)
            writeInt(WireProtocol.MAX_PCM_PAYLOAD + 1)
            writeInt(1)
        }
        WireProtocol.readFrame(ByteArrayInputStream(output.toByteArray()))
    }

    @Test
    fun authenticatesHelloAndFormat() {
        val token = ByteArray(32) { it.toByte() }
        val payload = ByteArrayOutputStream().also { raw ->
            DataOutputStream(raw).apply {
                write(token)
                writeInt(48_000)
                writeByte(2)
                writeByte(16)
                writeShort(0)
            }
        }.toByteArray()
        val hello = WireProtocol.parseHello(
            WireProtocol.Frame(WireProtocol.Type.HELLO, 1, payload),
            token,
        )

        assertEquals(48_000, hello.sampleRate)
        assertEquals(2, hello.channels)
        assertEquals(16, hello.bitsPerSample)
    }

    @Test(expected = ProtocolException::class)
    fun rejectsWrongSessionToken() {
        val expected = ByteArray(32) { 1 }
        val provided = ByteArray(32) { 2 }
        val payload = helloPayload(provided, 48_000, 2, 16)
        WireProtocol.parseHello(
            WireProtocol.Frame(WireProtocol.Type.HELLO, 1, payload),
            expected,
        )
    }

    @Test(expected = ProtocolException::class)
    fun rejectsUnsupportedVersion() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).apply {
            writeInt(WireProtocol.MAGIC)
            writeShort(WireProtocol.VERSION + 1)
            writeShort(WireProtocol.Type.PING.id)
            writeInt(0)
            writeInt(1)
        }
        WireProtocol.readFrame(ByteArrayInputStream(output.toByteArray()))
    }

    @Test(expected = EOFException::class)
    fun rejectsTruncatedPayload() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).apply {
            writeInt(WireProtocol.MAGIC)
            writeShort(WireProtocol.VERSION)
            writeShort(WireProtocol.Type.PCM.id)
            writeInt(4)
            writeInt(1)
            writeByte(0)
        }
        WireProtocol.readFrame(ByteArrayInputStream(output.toByteArray()))
    }

    @Test
    fun parsesConcatenatedFramesWithoutLosingBoundaries() {
        val output = ByteArrayOutputStream()
        WireProtocol.writeFrame(
            output,
            WireProtocol.Frame(WireProtocol.Type.PING, 3, byteArrayOf(0x0A)),
        )
        WireProtocol.writeFrame(
            output,
            WireProtocol.Frame(WireProtocol.Type.STOP, 4, ByteArray(0)),
        )
        val input = ByteArrayInputStream(output.toByteArray())

        assertEquals(WireProtocol.Type.PING, WireProtocol.readFrame(input)!!.type)
        assertEquals(WireProtocol.Type.STOP, WireProtocol.readFrame(input)!!.type)
        assertNull(WireProtocol.readFrame(input))
    }

    private fun helloPayload(
        token: ByteArray,
        sampleRate: Int,
        channels: Int,
        bits: Int,
    ): ByteArray = ByteArrayOutputStream().also { raw ->
        DataOutputStream(raw).apply {
            write(token)
            writeInt(sampleRate)
            writeByte(channels)
            writeByte(bits)
            writeShort(0)
        }
    }.toByteArray()
}
