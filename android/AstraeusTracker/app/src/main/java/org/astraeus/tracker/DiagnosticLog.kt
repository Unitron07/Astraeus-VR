package org.astraeus.tracker

import java.io.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Length-prefixed binary records; never block tracking callbacks on storage. */
class DiagnosticLog(file: File) : AutoCloseable {
    private val queue=ArrayBlockingQueue<ByteArray>(512)
    val drops=AtomicInteger()
    @Volatile var error=""; private set
    @Volatile private var running=true
    private val stream=DataOutputStream(BufferedOutputStream(FileOutputStream(file),65536))
    private val worker=Thread({
        try {
            var flush=System.nanoTime()
            while(running || queue.isNotEmpty()) {
                queue.poll(100,TimeUnit.MILLISECONDS)?.let { stream.writeInt(it.size); stream.write(it) }
                if(System.nanoTime()-flush>1_000_000_000L) { stream.flush(); flush=System.nanoTime() }
            }
        } catch(e: Exception) { error="Log write failed: ${e.message}" }
        finally { stream.close() }
    },"astraeus-log").apply { start() }
    fun offer(bytes: ByteArray) { if(error.isNotEmpty() || !queue.offer(bytes)) drops.incrementAndGet() }
    override fun close() { running=false; worker.join() }
}
