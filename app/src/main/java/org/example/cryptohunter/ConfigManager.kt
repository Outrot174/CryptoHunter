package org.example.cryptohunter

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

class ConfigManager(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "crypto_hunter_config",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var etherscanApiKey: String
        get() = prefs.getString("etherscan_api_key", "") ?: ""
        set(value) = prefs.edit().putString("etherscan_api_key", value).apply()

    var blockcypherApiKey: String
        get() = prefs.getString("blockcypher_api_key", "") ?: ""
        set(value) = prefs.edit().putString("blockcypher_api_key", value).apply()

    var infuraProjectId: String
        get() = prefs.getString("infura_project_id", "") ?: ""
        set(value) = prefs.edit().putString("infura_project_id", value).apply()

    var lastSeed: String
        get() = prefs.getString("last_seed", "") ?: ""
        set(value) = prefs.edit().putString("last_seed", value).apply()

    var lastBtcAddr: String
        get() = prefs.getString("last_btc_addr", "") ?: ""
        set(value) = prefs.edit().putString("last_btc_addr", value).apply()

    var lastEthAddr: String
        get() = prefs.getString("last_eth_addr", "") ?: ""
        set(value) = prefs.edit().putString("last_eth_addr", value).apply()

    var lastLtcAddr: String
        get() = prefs.getString("last_ltc_addr", "") ?: ""
        set(value) = prefs.edit().putString("last_ltc_addr", value).apply()

    var lastDogeAddr: String
        get() = prefs.getString("last_doge_addr", "") ?: ""
        set(value) = prefs.edit().putString("last_doge_addr", value).apply()

    var addrCount: Int
        get() = prefs.getInt("addr_count", 3)
        set(value) = prefs.edit().putInt("addr_count", value).apply()

    var permCount: Int
        get() = prefs.getInt("perm_count", 2)
        set(value) = prefs.edit().putInt("perm_count", value).apply()

    var saveInputs: Boolean
        get() = prefs.getBoolean("save_inputs", true)
        set(value) = prefs.edit().putBoolean("save_inputs", value).apply()

    fun clearHistory() {
        lastSeed = ""
        lastBtcAddr = ""
        lastEthAddr = ""
        lastLtcAddr = ""
        lastDogeAddr = ""
        val resultsFile = File(context.filesDir, "crypto_hunter_results.json")
        if (resultsFile.exists()) resultsFile.delete()
    }

    fun saveEncryptedResults(jsonContent: String) {
        prefs.edit().putString("saved_results_json", jsonContent).apply()
        File(context.filesDir, "crypto_hunter_results.json").writeText(jsonContent)
    }
}
