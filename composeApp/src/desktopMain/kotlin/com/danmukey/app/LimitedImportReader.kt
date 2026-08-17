package com.danmukey.app

import com.danmukey.shared.data.ImportFileLimits
import com.danmukey.shared.data.ImportPayloadKind
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readLimitedImportBytes(kind: ImportPayloadKind): ByteArray {
    val maximum = ImportFileLimits.maximumBytes(kind)
    val output = ByteArrayOutputStream(minOf(maximum, 64 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count <= 0) break
        total += count
        ImportFileLimits.requireWithin(kind, total)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
