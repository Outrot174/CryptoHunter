# crypto_utils.py
from mnemonic import Mnemonic
from itertools import permutations
from web3 import Web3
from bip44 import Wallet
from bitcoinlib.wallets import Wallet as BitcoinWallet
from bitcoinlib.keys import Address
from constants import COIN_NETWORKS
import logging

logger = logging.getLogger(__name__)

def validate_seed_phrase(seed_phrase):
    """Проверка сид-фразы на соответствие BIP-39."""
    mnemo = Mnemonic("english")
    return mnemo.check(seed_phrase)

def validate_address(coin, address):
    """Проверка валидности адреса для указанной криптовалюты."""
    if coin == 'Ethereum':
        return Web3.is_address(address)
    elif coin in COIN_NETWORKS:
        network_map = {
            'Bitcoin': 'bitcoin',
            'Litecoin': 'litecoin',
            'Dogecoin': 'dogecoin'
        }
        try:
            Address(address, network=network_map[coin])
            return True
        except:
            return False
    return False

def seed_to_priv_and_address(seed_phrase, coin, count):
    """Генерация приватных ключей и адресов из сид-фразы."""
    results = []
    try:
        if coin == 'Ethereum':
            wallet = Wallet(seed_phrase)
            for i in range(count):
                private_key, address = wallet.derive_account("eth", account=i)
                results.append((private_key.hex(), address))
        elif coin in COIN_NETWORKS:
            network_map = {
                'Bitcoin': 'bitcoin',
                'Litecoin': 'litecoin',
                'Dogecoin': 'dogecoin'
            }
            network = network_map[coin]
            wallet = BitcoinWallet.create(f"wallet_{coin}_{i}", keys=seed_phrase, network=network, witness_type='segwit')
            for i in range(count):
                address_obj = wallet.get_key(account=i)
                address = address_obj.address
                wif = address_obj.wif
                results.append((wif, address))
    except Exception as e:
        logger.error(f"Key generation error for {coin}: {e}")
    return results

def permute_seed(seed_phrase, max_permutations=5):
    """Генерация перестановок сид-фразы."""
    words = seed_phrase.split()
    if len(words) < 12:
        return [seed_phrase]
    perms = set([seed_phrase])
    for p in permutations(words, len(words)):
        perms.add(" ".join(p))
        if len(perms) >= max_permutations:
            break
    return list(perms)
