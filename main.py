# main.py
import logging
from kivy.app import App
from kivy.core.window import Window
from config_manager import ConfigManager
from ui_layout import CryptoHunterLayout

# Настройка логирования
logging.basicConfig(
    level=logging.INFO,
    filename='crypto_hunter.log',
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)
logger.warning("This application is for wallet recovery only. Unauthorized use may be illegal.")

# Проверка зависимостей
required_modules = ['kivy', 'requests', 'web3', 'bitcoinlib', 'cryptography', 'mnemonic', 'ratelimiter', 'tenacity']
for module in required_modules:
    try:
        __import__(module)
    except ImportError:
        print(f"Error: Required module '{module}' is not installed.")
        exit(1)

class CryptoHunterApp(App):
    """Основное приложение Crypto Hunter."""
    def build(self):
        self.config = ConfigManager()
        self.layout = CryptoHunterLayout(self.config)
        return self.layout

    def on_stop(self):
        self.layout.on_stop()

if __name__ == '__main__':
    CryptoHunterApp().run()
