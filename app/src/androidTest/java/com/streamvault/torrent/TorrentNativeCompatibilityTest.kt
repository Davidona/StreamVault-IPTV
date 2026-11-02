package com.streamvault.torrent

import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.AlertListener
import org.libtorrent4j.LibTorrent
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.ReadPieceAlert
import org.libtorrent4j.swig.settings_pack

/** Exercises JNI loading, native hashing, disk checking and piece reads without public peers. */
@RunWith(AndroidJUnit4::class)
class TorrentNativeCompatibilityTest {
    @Test
    fun hashesChecksAndReadsLocalTorrentOnDevicePageSize() {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        InstrumentationRegistry.getArguments().getString("expectedPageSize")?.let {
            assertEquals("Device must use the requested page size", it.toLong(), pageSize)
        }
        Log.i("TorrentNativeCompatibility", "api=${Build.VERSION.SDK_INT} pageSize=$pageSize libtorrent4j=${LibTorrent.libtorrent4jVersion()}")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "torrent-native-${System.nanoTime()}").apply { mkdirs() }
        val session = SessionManager()
        try {
            val payload = ByteArray(256 * 1024 + 37) { (it * 31).toByte() }
            val file = File(directory, "fixture.bin").apply { writeBytes(payload) }
            val metadata = TorrentBuilder().path(file).pieceSize(16 * 1024)
                .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
            val info = TorrentInfo(metadata)
            assertTrue(info.isValid)
            assertEquals(payload.size.toLong(), info.totalSize())
            val settings = SettingsPack().listenInterfaces("127.0.0.1:0")
            for (flag in listOf(
                settings_pack.bool_types.enable_dht,
                settings_pack.bool_types.enable_lsd,
                settings_pack.bool_types.enable_upnp,
                settings_pack.bool_types.enable_natpmp,
            )) settings.setBoolean(flag.swigValue(), false)
            val reads = LinkedBlockingQueue<Triple<Int, Int, Int>>()
            session.addListener(object : AlertListener {
                override fun types(): IntArray? = null
                override fun alert(alert: Alert<*>) {
                    if (alert is ReadPieceAlert) reads.offer(Triple(alert.piece(), alert.size(), alert.error().value))
                }
            })
            session.start(SessionParams(settings))
            session.download(info, directory)
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            var handle = session.find(info.infoHash())
            while (SystemClock.elapsedRealtime() < deadline) {
                handle = session.find(info.infoHash())
                if (handle?.isValid == true && (0 until info.numPieces()).all { handle.havePiece(it) }) break
                SystemClock.sleep(100L)
            }
            assertNotNull("Local torrent must be added", handle)
            val ready = requireNotNull(handle)
            assertTrue("All local pieces must pass native hash checking", (0 until info.numPieces()).all { ready.havePiece(it) })
            val lastPiece = info.numPieces() - 1
            ready.readPiece(lastPiece)
            val read = reads.poll(10, TimeUnit.SECONDS)
            assertNotNull("Native disk read must deliver a piece", read)
            assertEquals(Triple(lastPiece, 37, 0), read)
            assertArrayEquals(payload, file.readBytes())
            session.remove(ready)
        } finally {
            session.stop()
            directory.deleteRecursively()
        }
        assertFalse(session.isRunning)
    }
}
