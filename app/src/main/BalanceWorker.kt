package org.example.cryptohunter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

data class AddressResult(
    val coin: String,
    val address: String,
    val balance: Double,
    val unit: String,
    val transferred: Boolean = false,
    val txHash: String? = null
)

class BalanceWorker(private val config: ConfigManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val balanceCache = mutableMapOf<String, Double>()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun runCheck(
        seedPhrase: String,
        addrCount: Int,
        permCount: Int,
        targetAddrs: Map<String, String>,
        onProgress: (Float, String) -> Unit
    ): Map<String, AddressResult> = withContext(Dispatchers.IO) {
        val results = linkedMapOf<String, AddressResult>()
        val permutations = CryptoUtils.permuteSeed(seedPhrase, permCount)
        val totalOps = (permutations.size * Constants.COINS.size * addrCount).coerceAtLeast(1)
        var completedOps = 0

        for ((permIdx, perm) in permutations.withIndex()) {
            ensureActive()
            for (coin in Constants.COINS) {
                ensureActive()
                val accounts = CryptoUtils.seedToPrivAndAddress(perm, coin, addrCount)
                for ((addrIdx, account) in accounts.withIndex()) {
                    ensureActive()
                    completedOps++
                    val progress = completedOps.toFloat() / totalOps
                    onProgress(
                        progress,
                        "Checking: ${permIdx + 1}/${permutations.size} $coin ${addrIdx + 1}/$addrCount"
                    )

                    val balance = checkBalanceWithRetry(coin, account.address)
                    if (balance != null && balance > 0.0) {
                        val unit = Constants.COIN_UNITS[coin] ?: coin
                        var txHash: String? = null
                        var transferred = false
                        val targetAddr = targetAddrs[coin]?.trim().orEmpty()

                        if (targetAddr.isNotEmpty() && CryptoUtils.validateAddress(coin, targetAddr)) {
                            onProgress(progress, "Transferring $coin from ${account.address.take(10)}...")
                            txHash = sendCoin(coin, account, targetAddr)
                            transferred = !txHash.isNullOrEmpty()
                        }

                        results[account.address] = AddressResult(
                            coin = coin,
                            address = account.address,
                            balance = balance,
                            unit = unit,
                            transferred = transferred,
                            txHash = txHash
                        )
                    }
                    delay(250)
                }
            }
        }

        saveResultsSafely(results)
        results
    }

    private suspend fun checkBalanceWithRetry(coin: String, address: String): Double? {
        val cacheKey = "$coin:$address"
        balanceCache[cacheKey]?.let { return it }

        repeat(3) { attempt ->
            try {
                val balance = when (coin) {
                    "Bitcoin" -> fetchBtcBalance(address)
                    "Ethereum" -> fetchEthBalance(address)
                    "Litecoin" -> fetchBlockCypherBalance("ltc", address)
                    "Dogecoin" -> fetchBlockCypherBalance("doge", address)
                    else -> null
                }
                if (balance != null) {
                    balanceCache[cacheKey] = balance
                    return balance
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (attempt < 2) delay(1500)
            }
        }
        return null
    }

    private fun fetchBtcBalance(address: String): Double? {
        val req = Request.Builder().url(Constants.btcBalanceUrl(address)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: return null)
            val stats = json.getJSONObject("chain_stats")
            val funded = stats.optLong("funded_txo_sum", 0L)
            val spent = stats.optLong("spent_txo_sum", 0L)
            return (funded - spent).coerceAtLeast(0L) / 1e8
        }
    }

    private fun fetchBlockCypherBalance(coinSymbol: String, address: String): Double? {
        val url = Constants.blockCypherBalanceUrl(coinSymbol, address, config.blockcypherApiKey)
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: return null)
            return json.optLong("final_balance", 0L).coerceAtLeast(0L) / 1e8
        }
    }

    private fun getEthRpcUrl(): String {
        val infura = config.infuraProjectId.trim()
        return if (infura.isNotEmpty()) {
            "https://mainnet.infura.io/v3/$infura"
        } else {
            "https://cloudflare-eth.com"
        }
    }

    private fun fetchEthBalance(address: String): Double? {
        val balanceWei = ethRpcCall("eth_getBalance", JSONArray().put(address).put("latest"))
            ?: return null
        val wei = BigInteger(balanceWei.removePrefix("0x").ifEmpty { "0" }, 16)
        return BigDecimal(wei).divide(BigDecimal.TEN.pow(18), 8, RoundingMode.DOWN).toDouble()
    }

    private fun ethRpcCall(method: String, params: JSONArray): String? {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params)
            .toString()

        val req = Request.Builder()
            .url(getEthRpcUrl())
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: return null)
            if (json.has("error")) return null
            return json.optString("result", null)
        }
    }

    private fun sendCoin(coin: String, account: DerivedAccount, targetAddress: String): String? {
        return try {
            when (coin) {
                "Ethereum" -> sendEth(account, targetAddress)
                "Bitcoin" -> sendUtxoBlockCypher("btc", account, targetAddress)
                "Litecoin" -> sendUtxoBlockCypher("ltc", account, targetAddress)
                "Dogecoin" -> sendUtxoBlockCypher("doge", account, targetAddress)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sendEth(account: DerivedAccount, targetAddress: String): String? {
        val balHex = ethRpcCall("eth_getBalance", JSONArray().put(account.address).put("latest")) ?: return null
        val gpHex = ethRpcCall("eth_gasPrice", JSONArray()) ?: return null
        val nonceHex = ethRpcCall("eth_getTransactionCount", JSONArray().put(account.address).put("pending")) ?: return null

        val balanceWei = BigInteger(balHex.removePrefix("0x").ifEmpty { "0" }, 16)
        val gasPrice = BigInteger(gpHex.removePrefix("0x").ifEmpty { "0" }, 16)
        val gasLimit = BigInteger.valueOf(21000L)
        val fee = gasPrice.multiply(gasLimit)

        if (balanceWei <= fee) return null
        val sendValue = balanceWei.subtract(fee)
        val nonce = BigInteger(nonceHex.removePrefix("0x").ifEmpty { "0" }, 16)

        val rawSignedHex = CryptoUtils.signEthLegacyTx(
            nonce = nonce,
            gasPrice = gasPrice,
            gasLimit = gasLimit,
            toAddress = targetAddress,
            value = sendValue,
            privKeyHex = account.privateKeyHex
        )

        return ethRpcCall("eth_sendRawTransaction", JSONArray().put(rawSignedHex))
    }

    private fun sendUtxoBlockCypher(coinSymbol: String, account: DerivedAccount, targetAddress: String): String? {
        val token = config.blockcypherApiKey
        val newTxPayload = JSONObject()
            .put("inputs", JSONArray().put(JSONObject().put("addresses", JSONArray().put(account.address))))
            .put("outputs", JSONArray().put(JSONObject().put("addresses", JSONArray().put(targetAddress)).put("value", -1)))

        val newReq = Request.Builder()
            .url(Constants.blockCypherNewTxUrl(coinSymbol, token))
            .post(newTxPayload.toString().toRequestBody(jsonMediaType))
            .build()

        val tmpl = client.newCall(newReq).execute().use { resp ->
            if (!resp.isSuccessful) return null
            JSONObject(resp.body?.string() ?: return null)
        }

        val toSign = tmpl.optJSONArray("tosign") ?: return null
        val signatures = JSONArray()
        val pubkeys = JSONArray()

        for (i in 0 until toSign.length()) {
            val hashHex = toSign.getString(i)
            signatures.put(CryptoUtils.signHashDerHex(hashHex, account.privateKeyHex))
            pubkeys.put(account.publicKeyHex)
        }

        tmpl.put("signatures", signatures)
        tmpl.put("pubkeys", pubkeys)

        val sendReq = Request.Builder()
            .url(Constants.blockCypherSendTxUrl(coinSymbol, token))
            .post(tmpl.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(sendReq).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val resJson = JSONObject(resp.body?.string() ?: return null)
            return resJson.optJSONObject("tx")?.optString("hash")
        }
    }

    private fun saveResultsSafely(results: Map<String, AddressResult>) {
        val root = JSONObject()
        for ((addr, res) in results) {
            val item = JSONObject()
                .put("coin", res.coin)
                .put("balance", res.balance)
                .put("unit", res.unit)
                .put("transferred", res.transferred)
            if (res.txHash != null) item.put("tx_hash", res.txHash)
            root.put(addr, item)
        }
        config.saveEncryptedResults(root.toString(2))
    }
}
