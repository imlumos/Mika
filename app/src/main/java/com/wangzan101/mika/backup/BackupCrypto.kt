package com.wangzan101.mika.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 密码不对,或文件头到第一段就坏了:两者分不开。 */
class BackupPasswordException : IOException("wrong password or damaged backup")

/** 文件不完整或中途被改过。 */
class BackupDamagedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 备份文件的加密层:PBKDF2 从密码派生 AES-256 密钥,内容分段做 AES-GCM。
 * 每段的序号和「是否最后一段」进认证数据,段被调换、删掉或文件被截断都读得出来。
 *
 * 文件结构:魔数(8) 迭代次数(4) 盐(16) 随机串(8),之后每段是 是否最后(1) 长度(4) 密文。
 */
internal object BackupCrypto {
    const val DEFAULT_ITERATIONS = 600_000
    private val MAGIC = "MIKABAK1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val PREFIX_BYTES = 8
    private const val HEADER_BYTES = 36
    private const val CHUNK_BYTES = 1 shl 20
    private const val TAG_BITS = 128
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 10_000_000

    fun encrypting(out: OutputStream, password: String, iterations: Int = DEFAULT_ITERATIONS, random: SecureRandom = SecureRandom()): OutputStream {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val prefix = ByteArray(PREFIX_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putInt(iterations).put(salt).put(prefix).array()
        out.write(header)
        return EncryptingStream(DataOutputStream(out), key(password, salt, iterations), prefix, header)
    }

    fun decrypting(input: InputStream, password: String): InputStream {
        val data = DataInputStream(input)
        val header = ByteArray(HEADER_BYTES)
        try { data.readFully(header) } catch (e: EOFException) { throw BackupDamagedException("not a Mika backup", e) }
        val buffer = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC)) throw BackupDamagedException("not a Mika backup")
        val iterations = buffer.int
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BackupDamagedException("bad key parameters")
        val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
        val prefix = ByteArray(PREFIX_BYTES).also { buffer.get(it) }
        return DecryptingStream(data, key(password, salt, iterations), prefix, header)
    }

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, header: ByteArray, counter: Long, last: Boolean): Cipher {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(counter.toInt()).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
            updateAAD(ByteBuffer.allocate(9).putLong(counter).put(if (last) 1 else 0).array())
        }
    }

    private class EncryptingStream(
        private val out: DataOutputStream, private val key: SecretKeySpec, private val prefix: ByteArray, private val header: ByteArray,
    ) : OutputStream() {
        private val pending = ByteArrayOutputStream(CHUNK_BYTES)
        private var counter = 0L
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off
            var left = len
            while (left > 0) {
                val n = minOf(left, CHUNK_BYTES - pending.size())
                pending.write(b, at, n)
                at += n
                left -= n
                if (pending.size() == CHUNK_BYTES) chunk(false)
            }
        }

        private fun chunk(last: Boolean) {
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, prefix, header, counter++, last).doFinal(pending.toByteArray())
            pending.reset()
            out.writeByte(if (last) 1 else 0)
            out.writeInt(sealed.size)
            out.write(sealed)
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            chunk(true)
            out.close()
        }
    }

    private class DecryptingStream(
        private val data: DataInputStream, private val key: SecretKeySpec, private val prefix: ByteArray, private val header: ByteArray,
    ) : InputStream() {
        private var chunk = ByteArray(0)
        private var at = 0
        private var counter = 0L
        private var done = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (at == chunk.size) {
                if (done) return -1
                nextChunk()
            }
            val n = minOf(len, chunk.size - at)
            System.arraycopy(chunk, at, b, off, n)
            at += n
            return n
        }

        private fun nextChunk() {
            val last: Boolean
            val sealed: ByteArray
            try {
                last = data.readByte().toInt() == 1
                val size = data.readInt()
                if (size !in 0..(CHUNK_BYTES + TAG_BITS / 8)) throw BackupDamagedException("bad chunk size")
                sealed = ByteArray(size).also(data::readFully)
            } catch (e: EOFException) {
                if (counter == 0L) throw BackupDamagedException("backup is empty", e)
                throw BackupDamagedException("backup is cut short", e)
            }
            try {
                chunk = cipher(Cipher.DECRYPT_MODE, key, prefix, header, counter, last).doFinal(sealed)
            } catch (e: GeneralSecurityException) {
                if (counter == 0L) throw BackupPasswordException()
                throw BackupDamagedException("backup was changed", e)
            }
            at = 0
            counter++
            done = last
        }

        override fun close() = data.close()
    }
}
