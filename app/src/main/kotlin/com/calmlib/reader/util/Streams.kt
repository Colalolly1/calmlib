package com.calmlib.reader.util

import java.io.InputStream

/**
 * Read up to [max] bytes.
 *
 * Deliberately NOT InputStream.readNBytes: that is API 33+, and this app runs
 * on API 31 devices (the Mudita Kompakt), where calling it throws
 * NoSuchMethodError — an Error, so it sails through `catch (e: Exception)` and
 * kills the process. It shipped in the TXT reader and the import format
 * sniffer, i.e. it would have crashed on the very first book a user added.
 */
fun InputStream.readUpTo(max: Int): ByteArray {
    val buf = ByteArray(max)
    var n = 0
    while (n < max) {
        val r = read(buf, n, max - n)
        if (r < 0) break
        n += r
    }
    return if (n == max) buf else buf.copyOf(n)
}
