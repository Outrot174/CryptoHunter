# config_manager.py
import os
import json
from cryptography.fernet import Fernet
import logging

logger = logging.getLogger(__name__)

class ConfigManager:
    """Управление конфигурацией приложения с шифрованием."""
    CONFIG_FILE = "crypto_hunter_config.json"
    KEY_FILE = "crypto_hunter_key.key"

    def __init__(self):
        if os.path.exists(self.KEY_FILE):
            with open(self.KEY_FILE, 'rb') as f:
                self.key = f.read()
        else:
            self.key = Fernet.generate_key()
            with open(self.KEY_FILE, 'wb') as f:
                f.write(self.key)
        self.cipher = Fernet(self.key)
        self.config = {
            "etherscan_api_key": "",
            "blockcypher_api_key": "",
            "infura_project_id": "",
            "last_seed": "",
            "last_btc_addr": "",
            "last_eth_addr": "",
            "last_ltc_addr": "",
            "last_doge_addr": "",
            "addr_count": 3,
            "perm_count": 2,
            "save_inputs": True
        }
        self.load_config()

    def load_config(self):
        """Загрузка конфигурации из зашифрованного файла."""
        try:
            if os.path.exists(self.CONFIG_FILE):
                with open(self.CONFIG_FILE, 'r') as f:
                    encrypted_data = f.read()
                decrypted_data = self.cipher.decrypt(encrypted_data.encode()).decode()
                self.config = json.loads(decrypted_data)
        except Exception as e:
            logger.error(f"Error loading config: {e}")

    def save_config(self):
        """Сохранение конфигурации в зашифрованный файл."""
        try:
            encrypted_data = self.cipher.encrypt(json.dumps(self.config).encode())
            with open(self.CONFIG_FILE, 'w') as f:
                f.write(encrypted_data.decode())
        except Exception as e:
            logger.error(f"Error saving config: {e}")

    def get(self, key, default=None):
        return self.config.get(key, default)

    def set(self, key, value):
        self.config[key] = value
        self.save_config()
