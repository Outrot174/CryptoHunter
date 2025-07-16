# constants.py
COIN_NETWORKS = {
    'Bitcoin': 'BTC',
    'Litecoin': 'LTC',
    'Dogecoin': 'DOGE'
}

API_ENDPOINTS = {
    'Bitcoin': {
        'balance': lambda addr: f"https://blockchain.info/balance?active={addr}",
        'unspent': lambda addr: f"https://blockchain.info/unspent?active={addr}",
        'broadcast': "https://blockchain.info/pushtx"
    },
    'Litecoin': {
        'balance': lambda addr: f"https://api.blockcypher.com/v1/ltc/main/addrs/{addr}/balance",
        'unspent': lambda addr: f"https://api.blockcypher.com/v1/ltc/main/addrs/{addr}?unspentOnly=true",
        'broadcast': "https://api.blockcypher.com/v1/ltc/main/txs/push"
    },
    'Dogecoin': {
        'balance': lambda addr: f"https://dogechain.info/api/v1/address/balance/{addr}",
        'unspent': lambda addr: f"https://dogechain.info/api/v1/address/unspent/{addr}",
        'broadcast': "https://dogechain.info/api/v1/send_tx"
    }
}

DEFAULT_FEES = {
    'Bitcoin': 10000,
    'Litecoin': 1000,
    'Dogecoin': 1000000
}

COIN_PRICES = {
    'Bitcoin': 50000,
    'Ethereum': 3000,
    'Litecoin': 150,
    'Dogecoin': 0.1
}
