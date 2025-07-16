# balance_worker.py
import threading
import requests
from web3 import Web3
from eth_account import Account
from bitcoinlib.transactions import Transaction
from ratelimiter import RateLimiter
from tenacity import retry, stop_after_attempt, wait_fixed
import logging
from constants import API_ENDPOINTS, DEFAULT_FEES, COIN_PRICES, COIN_NETWORKS
from crypto_utils import seed_to_priv_and_address, validate_address

logger = logging.getLogger(__name__)
rate_limiter = RateLimiter(max_calls=10, period=60)

class BalanceWorker(threading.Thread):
    """Рабочий поток для проверки балансов и отправки транзакций."""
    def __init__(self, args, callback, progress_callback, config_manager):
        super().__init__()
        self.args = args
        self.callback = callback
        self.progress_callback = progress_callback
        self.config = config_manager
        self.results = {}
        self.error = None
        self.stop_requested = False
        self.w3 = None
        self.balance_cache = {}
        self.lock = threading.Lock()
        self.cipher = self.config.cipher

        if self.config.get('infura_project_id'):
            self.w3 = Web3(Web3.HTTPProvider(f"https://mainnet.infura.io/v3/{self.config.get('infura_project_id')}"))
        else:
            logger.warning("Infura Project ID not set. Ethereum checks will be disabled.")

    def run(self):
        """Основной метод потока для проверки балансов и выполнения транзакций."""
        try:
            seed_phrase, addr_count, perm_count, target_addrs = self.args
            from crypto_utils import validate_seed_phrase
            if not validate_seed_phrase(seed_phrase):
                self.callback({}, "Invalid seed phrase format (BIP-39)")
                return
            from crypto_utils import permute_seed
            permutations = permute_seed(seed_phrase, perm_count)
            total_ops = len(permutations) * 4 * addr_count
            completed_ops = 0

            for perm_idx, perm in enumerate(permutations):
                if self.stop_requested:
                    break
                for coin_idx, coin in enumerate(['Bitcoin', 'Ethereum', 'Litecoin', 'Dogecoin']):
                    if self.stop_requested:
                        break
                    priv_keys_addrs = seed_to_priv_and_address(perm, coin, addr_count)
                    for addr_idx, (priv_key, addr) in enumerate(priv_keys_addrs):
                        if self.stop_requested:
                            break
                        balance_info = self.check_balance(coin, addr)
                        completed_ops += 1
                        progress = completed_ops / total_ops
                        with self.lock:
                            self.progress_callback(progress, f"Checking: {perm_idx+1}/{len(permutations)} {coin} {addr_idx+1}/{addr_count}")

                        if balance_info and balance_info['balance'] > 0:
                            self.results[addr] = {
                                'coin': coin,
                                'balance': balance_info['balance'],
                                'priv_key': priv_key,
                                'unit': balance_info['unit']
                            }
                            target_addr = target_addrs.get(coin)
                            if target_addr and target_addr.strip():
                                with self.lock:
                                    self.progress_callback(progress, f"Transferring {coin} from {addr[:10]}...")
                                tx_hash = self.send_coin(coin, priv_key, target_addr)
                                if tx_hash:
                                    self.results[addr]['tx_hash'] = tx_hash
                                    self.results[addr]['transferred'] = True
                                else:
                                    self.results[addr]['transferred'] = False
            self.save_results()
            self.callback(self.results, None)
        except Exception as e:
            logger.error(f"Worker error: {e}", exc_info=True)
            self.callback({}, str(e))

    def check_balance(self, coin, address):
        """Проверка баланса адреса для указанной криптовалюты."""
        cache_key = f"{coin}:{address}"
        if cache_key in self.balance_cache:
            return self.balance_cache[cache_key]
        try:
            if coin == 'Bitcoin':
                url = API_ENDPOINTS['Bitcoin']['balance'](address)
                data = self.fetch_json_sync(url)
                if data and address in data:
                    balance = data[address]['final_balance'] / 1e8
                    result = {'balance': balance, 'unit': 'BTC'}
                    self.balance_cache[cache_key] = result
                    return result
            elif coin == 'Ethereum':
                if not self.w3:
                    logger.warning("Ethereum checks disabled: Infura not configured")
                    return None
                balance_wei = self.w3.eth.get_balance(address)
                balance = self.w3.from_wei(balance_wei, 'ether')
                result = {'balance': float(balance), 'unit': 'ETH'}
                self.balance_cache[cache_key] = result
                return result
            elif coin == 'Litecoin':
                if not self.config.get('blockcypher_api_key'):
                    logger.warning("BlockCypher API key not set for Litecoin")
                url = API_ENDPOINTS['Litecoin']['balance'](address)
                headers = {'Authorization': f'Token {self.config.get("blockcypher_api_key")}'}
                data = self.fetch_json_sync(url, headers)
                if data:
                    balance = data.get('balance', 0) / 1e8
                    result = {'balance': balance, 'unit': 'LTC'}
                    self.balance_cache[cache_key] = result
                    return result
            elif coin == 'Dogecoin':
                url = API_ENDPOINTS['Dogecoin']['balance'](address)
                data = self.fetch_json_sync(url)
                if data and data.get('success') == 1:
                    balance = float(data.get('balance', 0))
                    result = {'balance': balance, 'unit': 'DOGE'}
                    self.balance_cache[cache_key] = result
                    return result
                else:
                    logger.error(f"Dogecoin API error: {data.get('error', 'Unknown error')}")
                    return None
        except requests.Timeout:
            logger.error(f"Timeout checking {coin} balance for {address}")
            return None
        except requests.HTTPError as e:
            logger.error(f"HTTP error checking {coin} balance: {e}")
            return None
        except ValueError as e:
            logger.error(f"Data parsing error for {coin}: {e}")
            return None
        except Exception as e:
            logger.error(f"Unexpected error checking {coin} balance: {e}")
            return None

    @retry(stop=stop_after_attempt(3), wait=wait_fixed(2))
    def fetch_json_sync(self, url, headers=None):
        """Получение JSON-данных с повторными попытками."""
        with rate_limiter:
            response = requests.get(url, headers=headers, timeout=15)
            response.raise_for_status()
            return response.json()

    def send_coin(self, coin, priv_key, target_address):
        """Отправка криптовалюты на целевой адрес."""
        if not validate_address(coin, target_address):
            logger.error(f"Invalid target address for {coin}: {target_address}")
            return None
        try:
            if coin == 'Ethereum':
                return self.send_eth(priv_key, target_address)
            elif coin in COIN_NETWORKS:
                return self.send_utxo_coin(coin, priv_key, target_address)
        except Exception as e:
            logger.error(f"Error sending {coin}: {e}")
            return None

    def send_eth(self, priv_key_hex, target_address):
        """Отправка Ethereum-транзакции."""
        if not self.w3:
            logger.error("Ethereum RPC not configured")
            return None
        try:
            account = Account.from_key(priv_key_hex)
            address = account.address
            balance_wei = self.w3.eth.get_balance(address)
            if balance_wei <= 0:
                logger.warning("No balance to send")
                return None
            gas_price = self.w3.eth.gas_price
            gas_limit = self.w3.eth.estimate_gas({
                'to': target_address,
                'value': balance_wei,
                'from': address
            })
            fee = gas_price * gas_limit
            if balance_wei <= fee:
                logger.warning("Insufficient balance for fee")
                return None
            amount = balance_wei - fee
            tx = {
                'to': target_address,
                'value': amount,
                'gas': gas_limit,
                'gasPrice': gas_price,
                'nonce': self.w3.eth.get_transaction_count(address),
                'chainId': 1
            }
            signed_tx = account.sign_transaction(tx)
            tx_hash = self.w3.eth.send_raw_transaction(signed_tx.rawTransaction)
            return tx_hash.hex()
        except Exception as e:
            logger.error(f"ETH send error: {e}")
            return None

    def send_utxo_coin(self, coin, priv_key_wif, target_address):
        """Отправка UTXO-монет (Bitcoin, Litecoin, Dogecoin)."""
        handlers = {
            'Bitcoin': self.send_bitcoin,
            'Litecoin': self.send_litecoin,
            'Dogecoin': self.send_dogecoin
        }
        handler = handlers.get(coin)
        if handler:
            return handler(priv_key_wif, target_address)
        return None

    def send_bitcoin(self, priv_key_wif, target_address):
        """Отправка Bitcoin-транзакции."""
        return self._send_utxo_generic('Bitcoin', priv_key_wif, target_address)

    def send_litecoin(self, priv_key_wif, target_address):
        """Отправка Litecoin-транзакции."""
        return self._send_utxo_generic('Litecoin', priv_key_wif, target_address)

    def send_dogecoin(self, priv_key_wif, target_address):
        """Отправка Dogecoin-транзакции."""
        return self._send_utxo_generic('Dogecoin', priv_key_wif, target_address)

    def _send_utxo_generic(self, coin, priv_key_wif, target_address):
        """Общая логика отправки UTXO-монет."""
        from bitcoinlib.wallets import Wallet
        network_map = {
            'Bitcoin': 'bitcoin',
            'Litecoin': 'litecoin',
            'Dogecoin': 'dogecoin'
        }
        network = network_map[coin]
        try:
            wallet = Wallet.import_key(priv_key_wif, network=network)
            address = wallet.get_key().address
            url = API_ENDPOINTS[coin]['unspent'](address)
            headers = {'Authorization': f'Token {self.config.get("blockcypher_api_key")}' if coin == 'Litecoin' else None}
            data = self.fetch_json_sync(url, headers)
            if not data:
                logger.error(f"No data for {coin} unspent outputs")
                return None
            unspents = []
            if coin == 'Bitcoin':
                if 'unspent_outputs' not in data:
                    return None
                for output in data['unspent_outputs']:
                    unspents.append({
                        'tx_hash': output['tx_hash_big_endian'],
                        'tx_output_n': output['tx_output_n'],
                        'value': output['value'],
                        'script': output['script']
                    })
            elif coin == 'Litecoin':
                if 'txrefs' not in data:
                    return None
                for output in data['txrefs']:
                    unspents.append({
                        'tx_hash': output['tx_hash'],
                        'tx_output_n': output['tx_output_n'],
                        'value': output['value'],
                        'script': None
                    })
            elif coin == 'Dogecoin':
                if 'unspent_outputs' not in data:
                    return None
                for output in data['unspent_outputs']:
                    unspents.append({
                        'tx_hash': output['tx_hash'],
                        'tx_output_n': output['tx_output_n'],
                        'value': int(float(output['value']) * 1e8),
                        'script': output['script']
                    })
            if not unspents:
                logger.warning("No unspent outputs found")
                return None
            total_amount = sum(u['value'] for u in unspents)
            if total_amount <= 0:
                logger.warning("Zero balance")
                return None
            tx = Transaction(network=network)
            for u in unspents:
                tx.add_input(u['tx_hash'], u['tx_output_n'], value=u['value'])
            fee = DEFAULT_FEES[coin]
            amount = total_amount - fee
            if amount <= 0:
                logger.warning("Insufficient balance for fee")
                return None
            tx.add_output(target_address, amount)
            tx.sign([priv_key_wif])
            raw_tx = tx.raw_hex()
            broadcast_url = API_ENDPOINTS[coin]['broadcast']
            if coin == 'Bitcoin':
                response = requests.post(broadcast_url, data={'tx': raw_tx})
            elif coin == 'Litecoin':
                headers = {'Authorization': f'Token {self.config.get("blockcypher_api_key")}'}
                response = requests.post(broadcast_url, json={'tx': raw_tx}, headers=headers)
            elif coin == 'Dogecoin':
                response = requests.post(broadcast_url, data={'tx': raw_tx})
            if response.status_code not in [200, 201]:
                logger.error(f"Broadcast failed: {response.status_code} - {response.text}")
                return None
            if coin == 'Bitcoin':
                return response.text.strip()
            elif coin == 'Litecoin':
                return response.json().get('tx', {}).get('hash')
            elif coin == 'Dogecoin':
                return response.json().get('txid')
        except Exception as e:
            logger.error(f"{coin} send error: {e}")
            return None

    def save_results(self):
        """Сохранение результатов в зашифрованный файл."""
        try:
            results_safe = {addr: {k: v for k, v in data.items() if k != 'priv_key'} for addr, data in self.results.items()}
            encrypted_data = self.cipher.encrypt(json.dumps(results_safe).encode())
            with open('crypto_hunter_results.json', 'w') as f:
                f.write(encrypted_data.decode())
            logger.info("Results saved to crypto_hunter_results.json")
        except Exception as e:
            logger.error(f"Error saving results: {e}")
