package com.wangzan101.mika.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** 备份说明:文件最前面一条,导入时不解开全文就能告诉用户里面有什么。 */
@Serializable
data class BackupManifest(
    val format: Int,
    val appVersionCode: Int,
    val appVersionName: String,
    val createdAtMs: Long,
    val groups: List<String>,
    /** 导出时界面语言的标签;跟随系统时为 null。 */
    val language: String? = null,
    /** 解不开、没能放进备份的项(比如密钥库已失效)。 */
    val skipped: List<String> = emptyList(),
)

/**
 * 解密后的内容:一条说明,然后一条条文件,最后一个结束标记。整体压缩。
 * 文件分块写,不必预先知道大小。不用 zip:外层已加密,用不上它的互通性,也不用防它的路径和解压炸弹那一套。
 */
internal class BackupWriter(output: OutputStream) : AutoCloseable {
    private val deflater = DeflaterOutputStream(output)
    private val out = DataOutputStream(deflater)

    fun manifest(manifest: BackupManifest) {
        val bytes = Json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    fun file(path: String, executable: Boolean, content: InputStream) {
        out.writeByte(RECORD_FILE)
        out.writeUTF(path)
        out.writeBoolean(executable)
        val buffer = ByteArray(BLOCK_BYTES)
        while (true) {
            val n = content.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            out.writeInt(n)
            out.write(buffer, 0, n)
        }
        out.writeInt(0)
    }

    /** 写结束标记并关掉流;少了它导入时当作不完整的备份。 */
    fun finish() {
        out.writeByte(RECORD_END)
        out.close()
    }

    override fun close() = out.close()
}

internal class BackupReader(input: InputStream) : AutoCloseable {
    private val raw = input
    private val data = DataInputStream(InflaterInputStream(input))

    fun manifest(): BackupManifest {
        val size = data.readInt()
        if (size !in 1..MAX_MANIFEST_BYTES) throw BackupDamagedException("bad manifest size")
        val bytes = ByteArray(size).also(data::readFully)
        return runCatching { LENIENT.decodeFromString(BackupManifest.serializer(), bytes.toString(Charsets.UTF_8)) }
            .getOrElse { throw BackupDamagedException("bad manifest", it) }
    }

    /** 逐个读文件;[onFile] 必须读完给它的流。读到结束标记后,还要确认加密层也到了最后一段。 */
    fun files(onFile: (path: String, executable: Boolean, content: InputStream) -> Unit) {
        while (true) {
            when (val record = data.readByte().toInt()) {
                RECORD_END -> {
                    val rest = ByteArray(BLOCK_BYTES)
                    while (raw.read(rest) >= 0) { /* 读到加密层的最后一段,截断的文件在这里报错 */ }
                    return
                }
                RECORD_FILE -> {
                    val path = data.readUTF()
                    val executable = data.readBoolean()
                    val content = BlockStream(data)
                    onFile(path, executable, content)
                    content.drain()
                }
                else -> throw BackupDamagedException("bad record $record")
            }
        }
    }

    override fun close() = data.close()

    private class BlockStream(private val data: DataInputStream) : InputStream() {
        private var left = 0
        private var ended = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (left == 0 && !ended) {
                left = data.readInt()
                if (left !in 0..BLOCK_BYTES) throw BackupDamagedException("bad block size")
                if (left == 0) ended = true
            }
            if (ended) return -1
            val n = data.read(b, off, minOf(len, left))
            if (n < 0) throw BackupDamagedException("backup is cut short")
            left -= n
            return n
        }

        fun drain() {
            val rest = ByteArray(BLOCK_BYTES)
            while (read(rest, 0, rest.size) >= 0) { /* 丢掉调用方没读的部分 */ }
        }
    }

    private companion object {
        val LENIENT = Json { ignoreUnknownKeys = true }
    }
}

private const val RECORD_END = 0
private const val RECORD_FILE = 1
private const val BLOCK_BYTES = 64 * 1024
private const val MAX_MANIFEST_BYTES = 1 shl 20
