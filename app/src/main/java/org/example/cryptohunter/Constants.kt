package org.example.cryptohunter

object Constants {
    val COINS = listOf("Bitcoin", "Ethereum", "Litecoin", "Dogecoin")

    val COIN_UNITS = mapOf(
        "Bitcoin" to "BTC",
        "Ethereum" to "ETH",
        "Litecoin" to "LTC",
        "Dogecoin" to "DOGE"
    )

    val COIN_PRICES = mapOf(
        "Bitcoin" to 85000.0,
        "Ethereum" to 3200.0,
        "Litecoin" to 95.0,
        "Dogecoin" to 0.25
    )

    fun btcBalanceUrl(addr: String) = "https://mempool.space/api/address/$addr"
    fun blockCypherBalanceUrl(coinSymbol: String, addr: String, token: String): String {
        val base = "https://api.blockcypher.com/v1/$coinSymbol/main/addrs/$addr/balance"
        return if (token.isNotBlank()) "$base?token=$token" else base
    }
    fun blockCypherNewTxUrl(coinSymbol: String, token: String): String {
        val base = "https://api.blockcypher.com/v1/$coinSymbol/main/txs/new"
        return if (token.isNotBlank()) "$base?token=$token" else base
    }
    fun blockCypherSendTxUrl(coinSymbol: String, token: String): String {
        val base = "https://api.blockcypher.com/v1/$coinSymbol/main/txs/send"
        return if (token.isNotBlank()) "$base?token=$token" else base
    }
}
