package org.example.cryptohunter

import android.content.Context
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer

data class DerivedAccount(
    val privateKeyHex: String,
    val publicKeyHex: String,
    val address: String
)

object CryptoUtils {
    private val curveParams = ECNamedCurveTable.getByName("secp256k1")
    private val domainParams = ECDomainParameters(
        curveParams.curve, curveParams.g, curveParams.n, curveParams.h
    )
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private var bip39Words: List<String>? = null

    fun initWordlist(context: Context) {
        if (bip39Words != null) return
        bip39Words = try {
            context.assets.open("bip39_english.txt").bufferedReader().readLines()
                .map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun validateSeedPhrase(seedPhrase: String): Boolean {
        val words = seedPhrase.trim().lowercase().split(Regex("\\s+"))
        if (words.size !in setOf(12, 15, 18, 21, 24)) return false
        val list = bip39Words
        if (list != null && list.size == 2048) {
            val indices = words.map { list.indexOf(it) }
            if (indices.any { it == -1 }) return false
            val totalBits = words.size * 11
            val entropyBits = totalBits * 32 / 33
            val checksumBits = totalBits - entropyBits
            val bits = StringBuilder()
            for (idx in indices) {
                bits.append(idx.toString(2).padStart(11, '0'))
            }
            val entropyBytes = ByteArray(entropyBits / 8)
            for (i in entropyBytes.indices) {
                entropyBytes[i] = bits.substring(i * 8, (i + 1) * 8).toInt(2).toByte()
            }
            val hash = sha256(entropyBytes)
            val hashBits = (hash[0].toInt() and 0xFF).toString(2).padStart(8, '0')
            return bits.substring(entropyBits) == hashBits.substring(0, checksumBits)
        }
        return words.all { it.matches(Regex("^[a-z]+$")) }
    }

    fun validateAddress(coin: String, address: String): Boolean {
        val clean = address.trim()
        if (clean.isEmpty()) return false
        return when (coin) {
            "Ethereum" -> clean.matches(Regex("^0x[0-9a-fA-F]{40}$"))
            "Bitcoin" -> clean.matches(Regex("^(1|3)[1-9A-HJ-NP-Za-km-z]{25,34}$")) ||
                    clean.matches(Regex("^bc1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{25,87}$"))
            "Litecoin" -> clean.matches(Regex("^(L|M)[1-9A-HJ-NP-Za-km-z]{25,34}$")) ||
                    clean.matches(Regex("^ltc1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{25,87}$"))
            "Dogecoin" -> clean.matches(Regex("^D[5-9A-HJ-NP-U][1-9A-HJ-NP-Za-km-z]{32}$"))
            else -> false
        }
    }

    fun permuteSeed(seedPhrase: String, maxPermutations: Int): List<String> {
        val words = seedPhrase.trim().lowercase().split(Regex("\\s+")).toMutableList()
        if (words.size < 12 || maxPermutations <= 1) return listOf(words.joinToString(" "))
        val result = linkedSetOf(words.joinToString(" "))
        outer@ for (i in words.indices) {
            for (j in i + 1 until words.size) {
                val copy = words.toMutableList()
                val tmp = copy[i]
                copy[i] = copy[j]
                copy[j] = tmp
                val candidate = copy.joinToString(" ")
                if (validateSeedPhrase(candidate)) {
                    result.add(candidate)
                    if (result.size >= maxPermutations) break@outer
                }
            }
        }
        if (result.size < maxPermutations) {
            for (i in 0 until words.size - 1) {
                val copy = words.toMutableList()
                val tmp = copy[i]
                copy[i] = copy[i + 1]
                copy[i + 1] = tmp
                result.add(copy.joinToString(" "))
                if (result.size >= maxPermutations) break
            }
        }
        return result.toList()
    }

    fun seedToPrivAndAddress(seedPhrase: String, coin: String, count: Int): List<DerivedAccount> {
        val seed = mnemonicToSeed(seedPhrase)
        val master = hmacSha512("Bitcoin seed".toByteArray(), seed)
        val masterKey = master.copyOfRange(0, 32)
        val masterChain = master.copyOfRange(32, 64)

        val path = when (coin) {
            "Ethereum" -> intArrayOf(44 or -0x80000000, 60 or -0x80000000, 0 or -0x80000000, 0)
            "Bitcoin" -> intArrayOf(84 or -0x80000000, 0 or -0x80000000, 0 or -0x80000000, 0)
            "Litecoin" -> intArrayOf(44 or -0x80000000, 2 or -0x80000000, 0 or -0x80000000, 0)
            "Dogecoin" -> intArrayOf(44 or -0x80000000, 3 or -0x80000000, 0 or -0x80000000, 0)
            else -> return emptyList()
        }

        var currKey = masterKey
        var currChain = masterChain
        for (idx in path) {
            val derived = deriveChild(currKey, currChain, idx)
            currKey = derived.first
            currChain = derived.second
        }

        val accounts = mutableListOf<DerivedAccount>()
        for (i in 0 until count) {
            val (childKey, _) = deriveChild(currKey, currChain, i)
            val privBig = BigInteger(1, childKey)
            val pubPoint = domainParams.g.multiply(privBig).normalize()
            val compressedPub = pubPoint.getEncoded(true)
            val uncompressedPub = pubPoint.getEncoded(false)

            val address = when (coin) {
                "Ethereum" -> {
                    val hash = keccak256(uncompressedPub.copyOfRange(1, uncompressedPub.size))
                    "0x" + hash.copyOfRange(12, 32).toHex()
                }
                "Bitcoin" -> encodeBech32("bc", 0, hash160(compressedPub))
                "Litecoin" -> encodeBase58Check(0x30.toByte(), hash160(compressedPub))
                "Dogecoin" -> encodeBase58Check(0x1E.toByte(), hash160(compressedPub))
                else -> ""
            }
            accounts.add(
                DerivedAccount(
                    privateKeyHex = childKey.toHex(),
                    publicKeyHex = compressedPub.toHex(),
                    address = address
                )
            )
        }
        return accounts
    }

    fun signHashDerHex(hashHex: String, privKeyHex: String): String {
        val hash = hashHex.hexToBytes()
        val priv = BigInteger(1, privKeyHex.hexToBytes())
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(priv, domainParams))
        val sig = signer.generateSignature(hash)
        val r = sig[0]
        val halfN = domainParams.n.shiftRight(1)
        val s = if (sig[1] > halfN) domainParams.n.subtract(sig[1]) else sig[1]

        val rBytes = r.toByteArray()
        val sBytes = s.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(0x30)
        out.write(rBytes.size + sBytes.size + 4)
        out.write(0x02)
        out.write(rBytes.size)
        out.write(rBytes)
        out.write(0x02)
        out.write(sBytes.size)
        out.write(sBytes)
        return out.toByteArray().toHex()
    }

    fun signEthLegacyTx(
        nonce: BigInteger,
        gasPrice: BigInteger,
        gasLimit: BigInteger,
        toAddress: String,
        value: BigInteger,
        privKeyHex: String,
        chainId: Long = 1L
    ): String {
        val toBytes = toAddress.removePrefix("0x").hexToBytes()
        val unsignedItems = listOf(
            rlpEncodeBigInt(nonce),
            rlpEncodeBigInt(gasPrice),
            rlpEncodeBigInt(gasLimit),
            rlpEncodeBytes(toBytes),
            rlpEncodeBigInt(value),
            rlpEncodeBytes(ByteArray(0)),
            rlpEncodeBigInt(BigInteger.valueOf(chainId)),
            rlpEncodeBigInt(BigInteger.ZERO),
            rlpEncodeBigInt(BigInteger.ZERO)
        )
        val rawUnsigned = rlpEncodeList(unsignedItems)
        val txHash = keccak256(rawUnsigned)

        val priv = BigInteger(1, privKeyHex.hexToBytes())
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(priv, domainParams))
        val sig = signer.generateSignature(txHash)
        val r = sig[0]
        val halfN = domainParams.n.shiftRight(1)
        val s = if (sig[1] > halfN) domainParams.n.subtract(sig[1]) else sig[1]

        val pubUncompressed = domainParams.g.multiply(priv).normalize().getEncoded(false)
        val recId = findRecoveryId(txHash, r, s, pubUncompressed)
        val v = BigInteger.valueOf(chainId * 2 + 35L + recId)

        val signedItems = listOf(
            rlpEncodeBigInt(nonce),
            rlpEncodeBigInt(gasPrice),
            rlpEncodeBigInt(gasLimit),
            rlpEncodeBytes(toBytes),
            rlpEncodeBigInt(value),
            rlpEncodeBytes(ByteArray(0)),
            rlpEncodeBigInt(v),
            rlpEncodeBigInt(r),
            rlpEncodeBigInt(s)
        )
        return "0x" + rlpEncodeList(signedItems).toHex()
    }

    private fun findRecoveryId(hash: ByteArray, r: BigInteger, s: BigInteger, expectedPub: ByteArray): Int {
        for (recId in 0..1) {
            val x = r
            if (x >= curveParams.curve.field.characteristic) continue
            val compressed = ByteArray(33)
            compressed[0] = (0x02 or recId).toByte()
            val xBytes = x.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
            System.arraycopy(xBytes, 0, compressed, 33 - xBytes.size, xBytes.size)
            try {
                val rPoint = curveParams.curve.decodePoint(compressed)
                if (!rPoint.multiply(domainParams.n).isInfinity) continue
                val e = BigInteger(1, hash)
                val eInv = BigInteger.ZERO.subtract(e).mod(domainParams.n)
                val rInv = r.modInverse(domainParams.n)
                val srInv = rInv.multiply(s).mod(domainParams.n)
                val eInvrInv = rInv.multiply(eInv).mod(domainParams.n)
                val q = domainParams.g.multiply(eInvrInv).add(rPoint.multiply(srInv)).normalize()
                if (q.getEncoded(false).contentEquals(expectedPub)) return recId
            } catch (_: Exception) {}
        }
        return 0
    }

    private fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val normMnemonic = Normalizer.normalize(mnemonic.trim(), Normalizer.Form.NFKD)
        val normSalt = Normalizer.normalize("mnemonic$passphrase", Normalizer.Form.NFKD)
        val gen = PKCS5S2ParametersGenerator(SHA512Digest())
        gen.init(normMnemonic.toByteArray(Charsets.UTF_8), normSalt.toByteArray(Charsets.UTF_8), 2048)
        return (gen.generateDerivedParameters(512) as KeyParameter).key
    }

    private fun deriveChild(parentKey: ByteArray, parentChain: ByteArray, index: Int): Pair<ByteArray, ByteArray> {
        val data = ByteBuffer.allocate(37)
        if (index < 0) {
            data.put(0.toByte())
            data.put(parentKey)
        } else {
            val pub = domainParams.g.multiply(BigInteger(1, parentKey)).normalize().getEncoded(true)
            data.put(pub)
        }
        data.putInt(index)
        val i = hmacSha512(parentChain, data.array())
        val il = BigInteger(1, i.copyOfRange(0, 32))
        val ir = i.copyOfRange(32, 64)
        val kPar = BigInteger(1, parentKey)
        val ki = il.add(kPar).mod(domainParams.n)
        val kiBytes = ki.toByteArray().let {
            when {
                it.size == 32 -> it
                it.size > 32 -> it.copyOfRange(it.size - 32, it.size)
                else -> ByteArray(32 - it.size) + it
            }
        }
        return kiBytes to ir
    }

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val hmac = HMac(SHA512Digest())
        hmac.init(KeyParameter(key))
        hmac.update(data, 0, data.size)
        val out = ByteArray(64)
        hmac.doFinal(out, 0)
        return out
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun hash160(data: ByteArray): ByteArray {
        val sha = sha256(data)
        val ripemd = RIPEMD160Digest()
        ripemd.update(sha, 0, sha.size)
        val out = ByteArray(20)
        ripemd.doFinal(out, 0)
        return out
    }

    private fun keccak256(data: ByteArray): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(data, 0, data.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    private fun encodeBase58Check(version: Byte, payload: ByteArray): String {
        val data = ByteArray(1 + payload.size)
        data[0] = version
        System.arraycopy(payload, 0, data, 1, payload.size)
        val checksum = sha256(sha256(data)).copyOfRange(0, 4)
        val full = data + checksum
        var num = BigInteger(1, full)
        val sb = StringBuilder()
        val base = BigInteger.valueOf(58)
        while (num > BigInteger.ZERO) {
            val rem = num.divideAndRemainder(base)
            num = rem[0]
            sb.append(ALPHABET[rem[1].toInt()])
        }
        for (b in full) {
            if (b == 0.toByte()) sb.append(ALPHABET[0]) else break
        }
        return sb.reverse().toString()
    }

    private fun encodeBech32(hrp: String, witnessVersion: Int, witnessProgram: ByteArray): String {
        val converted = convertBits(witnessProgram, 8, 5, true)
        val data = ByteArray(1 + converted.size)
        data[0] = witnessVersion.toByte()
        System.arraycopy(converted, 0, data, 1, converted.size)
        val checksum = createBech32Checksum(hrp, data)
        val combined = data + checksum
        val sb = StringBuilder(hrp).append('1')
        for (b in combined) sb.append(BECH32_CHARSET[b.toInt()])
        return sb.toString()
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = ByteArrayOutputStream()
        val maxv = (1 shl toBits) - 1
        for (b in data) {
            val value = b.toInt() and 0xFF
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.write((acc shr bits) and maxv)
            }
        }
        if (pad && bits > 0) {
            out.write((acc shl (toBits - bits)) and maxv)
        }
        return out.toByteArray()
    }

    private fun createBech32Checksum(hrp: String, values: ByteArray): ByteArray {
        val hrpExpanded = ByteArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            hrpExpanded[i] = (hrp[i].code shr 5).toByte()
            hrpExpanded[i + hrp.length + 1] = (hrp[i].code and 31).toByte()
        }
        val enc = hrpExpanded + values + ByteArray(6)
        val mod = bech32Polymod(enc) xor 1
        val ret = ByteArray(6)
        for (i in 0..5) ret[i] = ((mod shr (5 * (5 - i))) and 31).toByte()
        return ret
    }

    private fun bech32Polymod(values: ByteArray): Int {
        var chk = 1
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        for (v in values) {
            val top = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor (v.toInt() and 0xff)
            for (i in 0..4) {
                if (((top shr i) and 1) != 0) chk = chk xor gen[i]
            }
        }
        return chk
    }

    private fun rlpEncodeBigInt(v: BigInteger): ByteArray {
        if (v == BigInteger.ZERO) return ByteArray(1) { 0x80.toByte() }
        val raw = v.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return rlpEncodeBytes(raw)
    }

    private fun rlpEncodeBytes(bytes: ByteArray): ByteArray {
        return when {
            bytes.size == 1 && (bytes[0].toInt() and 0xFF) < 0x80 -> bytes
            bytes.size <= 55 -> ByteArray(1) { (0x80 + bytes.size).toByte() } + bytes
            else -> {
                val lenBytes = BigInteger.valueOf(bytes.size.toLong()).toByteArray()
                    .let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
                ByteArray(1) { (0xB7 + lenBytes.size).toByte() } + lenBytes + bytes
            }
        }
    }

    private fun rlpEncodeList(items: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        items.forEach { out.write(it) }
        val payload = out.toByteArray()
        return if (payload.size <= 55) {
            ByteArray(1) { (0xC0 + payload.size).toByte() } + payload
        } else {
            val lenBytes = BigInteger.valueOf(payload.size.toLong()).toByteArray()
                .let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
            ByteArray(1) { (0xF7 + lenBytes.size).toByte() } + lenBytes + payload
        }
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
