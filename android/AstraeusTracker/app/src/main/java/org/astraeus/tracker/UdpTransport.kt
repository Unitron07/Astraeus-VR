package org.astraeus.tracker

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/** Bounded latest-sample queue prevents network stalls accumulating pose latency. */
class UdpTransport : AutoCloseable {
    private val queue = ArrayBlockingQueue<List<ByteArray>>(1)
    private val socket = DatagramSocket()
    @Volatile private var running = true
    @Volatile var status = "Destination not configured"; private set
    @Volatile private var destination: Pair<InetAddress, Int>? = null
    val sent = AtomicLong()
    val dropped = AtomicLong()
    fun connect(ip: String, port: Int) {
        require(port in 1..65535) { "Port must be 1-65535" }
        val parts = ip.split('.')
        require(parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) { "Enter a numeric IPv4 address" }
        destination = InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray()) to port
        status = "UDP destination $ip:$port (receipt unconfirmed)"
    }
    fun offer(bytes: ByteArray) {
        offerBatch(listOf(bytes))
    }
    fun offerBatch(bytes: List<ByteArray>) {
        if (destination == null) return
        if (!queue.offer(bytes)) { queue.poll(); dropped.incrementAndGet(); queue.offer(bytes) }
    }
    private val worker = Thread({
        while (running) {
            try {
                val bytes = queue.take()
                val target = destination ?: continue
                bytes.forEach { packet ->
                    socket.send(DatagramPacket(packet, packet.size, target.first, target.second))
                    sent.incrementAndGet()
                }
                status = "Sending UDP (PC receipt unconfirmed)"
            } catch (_: InterruptedException) { break }
            catch (e: Exception) { status = "UDP error: ${e.message}" }
        }
    }, "pose-udp").apply { start() }
    override fun close() { running = false; socket.close(); worker.interrupt(); worker.join(500) }
}
