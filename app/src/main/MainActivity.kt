package org.example.cryptohunter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var config: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CryptoUtils.initWordlist(applicationContext)
        config = ConfigManager(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00B2FF),
                    secondary = Color(0xFF00B300),
                    background = Color(0xFF1A1A1A),
                    surface = Color(0xFF262626)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CryptoHunterApp(config)
                }
            }
        }
    }
}

@Composable
fun CryptoHunterApp(config: ConfigManager) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }

    var seedPhrase by remember { mutableStateOf(config.lastSeed) }
    var btcAddr by remember { mutableStateOf(config.lastBtcAddr) }
    var ethAddr by remember { mutableStateOf(config.lastEthAddr) }
    var ltcAddr by remember { mutableStateOf(config.lastLtcAddr) }
    var dogeAddr by remember { mutableStateOf(config.lastDogeAddr) }
    var addrCountText by remember { mutableStateOf(config.addrCount.toString()) }
    var permCountText by remember { mutableStateOf(config.permCount.toString()) }
    var saveInputs by remember { mutableStateOf(config.saveInputs) }

    var etherscanKey by remember { mutableStateOf(config.etherscanApiKey) }
    var blockcypherKey by remember { mutableStateOf(config.blockcypherApiKey) }
    var infuraId by remember { mutableStateOf(config.infuraProjectId) }

    var outputLog by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var progressValue by remember { mutableFloatStateOf(0f) }
    var progressStatus by remember { mutableStateOf("Starting...") }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Main") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Settings") })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("API Keys") })
        }

        when (selectedTab) {
            0 -> MainTabContent(
                seedPhrase = seedPhrase,
                onSeedChange = { seedPhrase = it },
                btcAddr = btcAddr,
                onBtcChange = { btcAddr = it },
                ethAddr = ethAddr,
                onEthChange = { ethAddr = it },
                ltcAddr = ltcAddr,
                onLtcChange = { ltcAddr = it },
                dogeAddr = dogeAddr,
                onDogeChange = { dogeAddr = it },
                addrCountText = addrCountText,
                onAddrCountChange = { addrCountText = it },
                permCountText = permCountText,
                onPermCountChange = { permCountText = it },
                outputLog = outputLog,
                onStartCheck = {
                    if (isProcessing) return@MainTabContent
                    val cleanSeed = seedPhrase.trim()
                    if (cleanSeed.isEmpty()) {
                        outputLog = "Error: Seed phrase is required!\n"
                        return@MainTabContent
                    }
                    if (!CryptoUtils.validateSeedPhrase(cleanSeed)) {
                        outputLog = "Error: Invalid seed phrase format (BIP-39)!\n"
                        return@MainTabContent
                    }
                    val addrCount = addrCountText.toIntOrNull()
                    val permCount = permCountText.toIntOrNull()
                    if (addrCount == null || addrCount !in 1..100 || permCount == null || permCount !in 1..10) {
                        outputLog = "Error: Address count (1..100) or Permutation count (1..10) is invalid!\n"
                        return@MainTabContent
                    }
                    val targets = mapOf(
                        "Bitcoin" to btcAddr.trim(),
                        "Ethereum" to ethAddr.trim(),
                        "Litecoin" to ltcAddr.trim(),
                        "Dogecoin" to dogeAddr.trim()
                    )
                    for ((coin, addr) in targets) {
                        if (addr.isNotEmpty() && !CryptoUtils.validateAddress(coin, addr)) {
                            outputLog = "Error: Invalid $coin address: $addr\n"
                            return@MainTabContent
                        }
                    }

                    if (saveInputs) {
                        config.lastSeed = cleanSeed
                        config.lastBtcAddr = targets["Bitcoin"]!!
                        config.lastEthAddr = targets["Ethereum"]!!
                        config.lastLtcAddr = targets["Litecoin"]!!
                        config.lastDogeAddr = targets["Dogecoin"]!!
                        config.addrCount = addrCount
                        config.permCount = permCount
                    }

                    outputLog = buildString {
                        appendLine("Starting check...")
                        appendLine("Seed: ${cleanSeed.take(12)}...")
                        appendLine("Addresses: $addrCount, Permutations: $permCount")
                        appendLine("-".repeat(45))
                    }

                    isProcessing = true
                    progressValue = 0f
                    progressStatus = "Starting..."

                    activeJob = scope.launch {
                        try {
                            val worker = BalanceWorker(config)
                            val results = worker.runCheck(
                                seedPhrase = cleanSeed,
                                addrCount = addrCount,
                                permCount = permCount,
                                targetAddrs = targets,
                                onProgress = { prog, status ->
                                    progressValue = prog
                                    progressStatus = status
                                }
                            )
                            if (results.isEmpty()) {
                                outputLog += "\nNo funds found on checked addresses.\n"
                            } else {
                                var totalUsd = 0.0
                                val sb = StringBuilder("\nResults:\n" + "=".repeat(45) + "\n")
                                for ((_, res) in results) {
                                    sb.appendLine("Coin: ${res.coin}")
                                    sb.appendLine("Address: ${res.address}")
                                    sb.appendLine("Balance: %.6f %s".format(res.balance, res.unit))
                                    if (res.txHash != null) {
                                        val st = if (res.transferred) "SUCCESS" else "FAILED"
                                        sb.appendLine("Transfer: $st")
                                        sb.appendLine("TX Hash: ${res.txHash}")
                                    }
                                    sb.appendLine("-".repeat(45))
                                    totalUsd += res.balance * (Constants.COIN_PRICES[res.coin] ?: 0.0)
                                }
                                sb.appendLine("\nTotal Approximate Value: $%.2f USD".format(totalUsd))
                                sb.appendLine("Results saved to encrypted storage (private keys excluded).")
                                outputLog += sb.toString()
                            }
                        } catch (_: CancellationException) {
                            outputLog += "\nOperation cancelled by user.\n"
                        } catch (e: Exception) {
                            outputLog += "\nError: ${e.message}\n"
                        } finally {
                            isProcessing = false
                        }
                    }
                }
            )

            1 -> SettingsTabContent(
                saveInputs = saveInputs,
                onToggleSave = {
                    saveInputs = it
                    config.saveInputs = it
                },
                onClearHistory = {
                    config.clearHistory()
                    seedPhrase = ""
                    btcAddr = ""
                    ethAddr = ""
                    ltcAddr = ""
                    dogeAddr = ""
                    outputLog = ""
                }
            )

            2 -> ApiKeysTabContent(
                etherscanKey = etherscanKey,
                onEtherscanChange = { etherscanKey = it },
                blockcypherKey = blockcypherKey,
                onBlockcypherChange = { blockcypherKey = it },
                infuraId = infuraId,
                onInfuraChange = { infuraId = it },
                onSaveKeys = {
                    config.etherscanApiKey = etherscanKey.trim()
                    config.blockcypherApiKey = blockcypherKey.trim()
                    config.infuraProjectId = infuraId.trim()
                }
            )
        }
    }

    if (isProcessing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Processing...") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(progressStatus, fontSize = 14.sp)
                    LinearProgressIndicator(
                        progress = progressValue,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${(progressValue * 100).toInt()}%", fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        activeJob?.cancel()
                        isProcessing = false
                    }
                ) {
                    Text("Cancel", color = Color(0xFFFF5555))
                }
            }
        )
    }
}

@Composable
fun ClearableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = modifier.fillMaxWidth(),
        trailingIcon = {
            if (value.isNotEmpty()) {
                TextButton(onClick = { onValueChange("") }) {
                    Text("✕", fontSize = 14.sp)
                }
            }
        }
    )
}

@Composable
fun MainTabContent(
    seedPhrase: String,
    onSeedChange: (String) -> Unit,
    btcAddr: String,
    onBtcChange: (String) -> Unit,
    ethAddr: String,
    onEthChange: (String) -> Unit,
    ltcAddr: String,
    onLtcChange: (String) -> Unit,
    dogeAddr: String,
    onDogeChange: (String) -> Unit,
    addrCountText: String,
    onAddrCountChange: (String) -> Unit,
    permCountText: String,
    onPermCountChange: (String) -> Unit,
    outputLog: String,
    onStartCheck: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Crypto Hunter",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        ClearableTextField(
            value = seedPhrase,
            onValueChange = onSeedChange,
            label = "Seed фраза (12-24 слова BIP-39)",
            singleLine = false
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClearableTextField(
                value = btcAddr,
                onValueChange = onBtcChange,
                label = "BTC Address",
                modifier = Modifier.weight(1f)
            )
            ClearableTextField(
                value = ethAddr,
                onValueChange = onEthChange,
                label = "ETH Address",
                modifier = Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClearableTextField(
                value = ltcAddr,
                onValueChange = onLtcChange,
                label = "LTC Address",
                modifier = Modifier.weight(1f)
            )
            ClearableTextField(
                value = dogeAddr,
                onValueChange = onDogeChange,
                label = "DOGE Address",
                modifier = Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClearableTextField(
                value = addrCountText,
                onValueChange = onAddrCountChange,
                label = "Address Count (1-100)",
                modifier = Modifier.weight(1f)
            )
            ClearableTextField(
                value = permCountText,
                onValueChange = onPermCountChange,
                label = "Permutations (1-10)",
                modifier = Modifier.weight(1f)
            )
        }

        Button(
            onClick = onStartCheck,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF009900))
        ) {
            Text("Check Balance and Transfer", fontWeight = FontWeight.Bold, color = Color.White)
        }

        Text("Results:", fontWeight = FontWeight.SemiBold)

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp),
            color = Color(0xFF121212),
            shape = MaterialTheme.shapes.small
        ) {
            SelectionContainer {
                Text(
                    text = outputLog.ifEmpty { "Ready." },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFDDDDDD),
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
fun SettingsTabContent(
    saveInputs: Boolean,
    onToggleSave: (Boolean) -> Unit,
    onClearHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Application Settings",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Save inputs on exit:")
            Switch(checked = saveInputs, onCheckedChange = onToggleSave)
        }

        Button(
            onClick = onClearHistory,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCC3333))
        ) {
            Text("Clear History and Results", color = Color.White, fontWeight = FontWeight.Bold)
        }

        Text(
            text = "Application Information:\n\n" +
                    "• Checks balances for Bitcoin, Ethereum, Litecoin, Dogecoin\n" +
                    "• Generates HD addresses in-memory (BIP-39 / BIP-44 / BIP-84)\n" +
                    "• Supports seed phrase permutations\n" +
                    "• Automatic transfers to specified addresses\n\n" +
                    "WARNING: Storing seed phrases can be risky. Disable 'Save inputs' for maximum security.",
            fontSize = 14.sp,
            color = Color(0xFFBBBBBB)
        )
    }
}

@Composable
fun ApiKeysTabContent(
    etherscanKey: String,
    onEtherscanChange: (String) -> Unit,
    blockcypherKey: String,
    onBlockcypherChange: (String) -> Unit,
    infuraId: String,
    onInfuraChange: (String) -> Unit,
    onSaveKeys: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var saveButtonText by remember { mutableStateOf("Save API Keys") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "API Key Configuration",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        ClearableTextField(
            value = etherscanKey,
            onValueChange = onEtherscanChange,
            label = "Etherscan API Key"
        )
        ClearableTextField(
            value = blockcypherKey,
            onValueChange = onBlockcypherChange,
            label = "BlockCypher API Key"
        )
        ClearableTextField(
            value = infuraId,
            onValueChange = onInfuraChange,
            label = "Infura Project ID"
        )

        Button(
            onClick = {
                onSaveKeys()
                saveButtonText = "Saved!"
                scope.launch {
                    delay(2000)
                    saveButtonText = "Save API Keys"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF009900))
        ) {
            Text(saveButtonText, color = Color.White, fontWeight = FontWeight.Bold)
        }

        HorizontalDivider()

        Text("API Status:", fontWeight = FontWeight.Bold)
        Text(
            text = buildString {
                appendLine(if (infuraId.isNotBlank()) "• Infura: Configured" else "• Infura: Public RPC fallback active")
                appendLine(if (etherscanKey.isNotBlank()) "• Etherscan: Configured" else "• Etherscan: Not configured")
                appendLine(if (blockcypherKey.isNotBlank()) "• BlockCypher: Configured" else "• BlockCypher: Public tier (rate limits apply)")
            },
            fontSize = 14.sp,
            color = Color(0xFFCCCCCC)
        )
    }
}
