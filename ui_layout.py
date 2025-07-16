# ui_layout.py
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.textinput import TextInput
from kivy.uix.button import Button
from kivy.uix.scrollview import ScrollView
from kivy.uix.popup import Popup
from kivy.uix.progressbar import ProgressBar
from kivy.uix.tabbedpanel import TabbedPanel, TabbedPanelItem
from kivy.uix.behaviors import TouchRippleBehavior
from kivy.properties import ObjectProperty, StringProperty, NumericProperty, BooleanProperty
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.config import Config
from balance_worker import BalanceWorker
from crypto_utils import validate_seed_phrase, validate_address
from constants import COIN_PRICES

# Настройка окна для мобильных устройств
Config.set('graphics', 'width', '600')
Config.set('graphics', 'height', '400')
Window.softinput_mode = 'pan'  # Поднимает интерфейс при открытии клавиатуры
Window.clearcolor = (0.1, 0.1, 0.1, 1)

class SwipeableTextInput(TouchRippleBehavior, TextInput):
    """TextInput с поддержкой очистки при двойном касании."""
    def on_touch_down(self, touch):
        if self.collide_point(*touch.pos) and touch.is_double_tap:
            self.text = ""  # Очистка при двойном касании
        super().on_touch_down(touch)

class ProgressPopup(Popup):
    """Всплывающее окно для отображения прогресса."""
    progress = NumericProperty(0)
    status = StringProperty("")
    cancel_btn = ObjectProperty(None)

    def __init__(self, cancel_callback, **kwargs):
        super().__init__(**kwargs)
        self.title = "Processing..."
        self.size_hint = (0.8, 0.3)
        self.cancel_callback = cancel_callback
        layout = BoxLayout(orientation='vertical', padding=10)
        self.progress_bar = ProgressBar(max=100)
        self.status_label = Label(text="Starting...", size_hint_y=None, height=30)
        btn_layout = BoxLayout(size_hint_y=None, height=40, spacing=10)
        self.cancel_btn = Button(text="Cancel", size_hint_x=0.5)
        self.cancel_btn.bind(on_press=self.cancel_action)
        btn_layout.add_widget(self.cancel_btn)
        layout.add_widget(self.status_label)
        layout.add_widget(self.progress_bar)
        layout.add_widget(btn_layout)
        self.add_widget(layout)

    def update_progress(self, value, status):
        self.progress = value * 100
        self.status = status
        self.status_label.text = status

    def cancel_action(self, instance):
        if self.cancel_callback:
            self.cancel_callback()
        self.dismiss()

class ApiKeyForm(BoxLayout):
    """Форма для ввода API-ключей."""
    def __init__(self, config, **kwargs):
        super().__init__(**kwargs)
        self.orientation = 'vertical'
        self.padding = 10
        self.spacing = 15
        self.config = config
        self.add_widget(Label(text='Etherscan API Key:', size_hint_y=None, height=30))
        self.etherscan_input = SwipeableTextInput(
            text=config.get('etherscan_api_key', ''),
            multiline=False, size_hint_y=None, height=40,
            background_color=(0.2, 0.2, 0.2, 1),
            foreground_color=(1, 1, 1, 1)
        )
        self.add_widget(self.etherscan_input)
        self.add_widget(Label(text='BlockCypher API Key:', size_hint_y=None, height=30))
        self.blockcypher_input = SwipeableTextInput(
            text=config.get('blockcypher_api_key', ''),
            multiline=False, size_hint_y=None, height=40,
            background_color=(0.2, 0.2, 0.2, 1),
            foreground_color=(1, 1, 1, 1)
        )
        self.add_widget(self.blockcypher_input)
        self.add_widget(Label(text='Infura Project ID:', size_hint_y=None, height=30))
        self.infura_input = SwipeableTextInput(
            text=config.get('infura_project_id', ''),
            multiline=False, size_hint_y=None, height=40,
            background_color=(0.2, 0.2, 0.2, 1),
            foreground_color=(1, 1, 1, 1)
        )
        self.add_widget(self.infura_input)
        self.save_btn = Button(
            text='Save API Keys', size_hint_y=None, height=60,
            background_color=(0, 0.7, 0, 1),
            background_down=(0, 0.5, 0, 1)
        )
        self.save_btn.bind(on_press=self.save_keys)
        self.add_widget(self.save_btn)
        self.info_label = Label(
            text="Note: Etherscan and BlockCypher keys are optional but recommended for better rate limits.",
            size_hint_y=None, height=50, color=(0.8, 0.8, 0.8, 1),
            halign='center', valign='middle'
        )
        self.info_label.bind(size=self.info_label.setter('text_size'))
        self.add_widget(self.info_label)

    def save_keys(self, instance):
        self.config.set('etherscan_api_key', self.etherscan_input.text.strip())
        self.config.set('blockcypher_api_key', self.blockcypher_input.text.strip())
        self.config.set('infura_project_id', self.infura_input.text.strip())
        self.save_btn.text = "Saved!"
        Clock.schedule_once(lambda dt: setattr(self.save_btn, 'text', 'Save API Keys'), 2)

class CryptoHunterLayout(TabbedPanel):
    """Основной интерфейс приложения."""
    results_text = StringProperty("")
    is_processing = BooleanProperty(False)

    def __init__(self, config, **kwargs):
        super().__init__(**kwargs)
        self.config = config
        self.worker = None
        self.progress_popup = None
        self.do_default_tab = False
        self.tab_width = 120
        self.add_widget(self.create_main_tab())
        self.add_widget(self.create_settings_tab())
        self.add_widget(self.create_api_tab())

    def create_main_tab(self):
        main_tab = TabbedPanelItem(text='Main')
        layout = BoxLayout(orientation='vertical', padding=10, spacing=10)
        layout.add_widget(Label(
            text="Crypto Hunter", font_size=18, bold=True, size_hint_y=None, height=40,
            color=(0, 0.7, 1, 1)
        ))
        seed_layout = BoxLayout(orientation='vertical', size_hint_y=None, height=100)
        seed_layout.add_widget(Label(text='Seed фраза:', size_hint_y=None, height=30))
        self.seed_input = SwipeableTextInput(
            text=self.config.get('last_seed', ''),
            multiline=True, size_hint_y=None, height=60,
            background_color=(0.15, 0.15, 0.15, 1),
            foreground_color=(1, 1, 1, 1),
            hint_text="Enter your seed phrase (12-24 words)"
        )
        self.seed_input.bind(on_text_validate=self.on_seed_validate)
        seed_layout.add_widget(self.seed_input)
        layout.add_widget(seed_layout)
        grid = BoxLayout(orientation='horizontal', size_hint_y=None, height=140, spacing=10)
        left_col = BoxLayout(orientation='vertical', spacing=10)
        right_col = BoxLayout(orientation='vertical', spacing=10)
        coins = ['Bitcoin', 'Ethereum', 'Litecoin', 'Dogecoin']
        for coin in coins[:2]:
            coin_layout = BoxLayout(orientation='vertical', size_hint_y=None, height=70)
            coin_layout.add_widget(Label(text=f'Withdrawal Address {coin}:', size_hint_y=None, height=30))
            ti = SwipeableTextInput(
                text=self.config.get(f'last_{coin.lower()}_addr', ''),
                multiline=False, size_hint_y=None, height=40,
                background_color=(0.15, 0.15, 0.15, 1),
                foreground_color=(1, 1, 1, 1),
                hint_text=f"Enter {coin} address"
            )
            ti.bind(on_text_validate=self.on_check)
            self.__setattr__(f'addr_input_{coin.lower()}', ti)
            coin_layout.add_widget(ti)
            left_col.add_widget(coin_layout)
        for coin in coins[2:]:
            coin_layout = BoxLayout(orientation='vertical', size_hint_y=None, height=70)
            coin_layout.add_widget(Label(text=f'Withdrawal Address {coin}:', size_hint_y=None, height=30))
            ti = SwipeableTextInput(
                text=self.config.get(f'last_{coin.lower()}_addr', ''),
                multiline=False, size_hint_y=None, height=40,
                background_color=(0.15, 0.15, 0.15, 1),
                foreground_color=(1, 1, 1, 1),
                hint_text=f"Enter {coin} address"
            )
            ti.bind(on_text_validate=self.on_check)
            self.__setattr__(f'addr_input_{coin.lower()}', ti)
            coin_layout.add_widget(ti)
            right_col.add_widget(coin_layout)
        grid.add_widget(left_col)
        grid.add_widget(right_col)
        layout.add_widget(grid)
        config_layout = BoxLayout(orientation='horizontal', size_hint_y=None, height=50, spacing=10)
        addr_layout = BoxLayout(orientation='vertical', size_hint_x=0.5)
        addr_layout.add_widget(Label(text='Address Count:', size_hint_y=None, height=20))
        self.addr_count_input = SwipeableTextInput(
            text="3", multiline=False, size_hint_y=None, height=30,
            background_color=(0.15, 0.15, 0.15, 1),
            foreground_color=(1, 1, 1, 1)
        )
        self.addr_count_input.bind(on_text_validate=self.on_check)
        addr_layout.add_widget(self.addr_count_input)
        config_layout.add_widget(addr_layout)
        perm_layout = BoxLayout(orientation='vertical', size_hint_x=0.5)
        perm_layout.add_widget(Label(text='Permutation Count:', size_hint_y=None, height=20))
        self.perm_count_input = SwipeableTextInput(
            text="2", multiline=False, size_hint_y=None, height=30,
            background_color=(0.15, 0.15, 0.15, 1),
            foreground_color=(1, 1, 1, 1)
        )
        self.perm_count_input.bind(on_text_validate=self.on_check)
        perm_layout.add_widget(self.perm_count_input)
        config_layout.add_widget(perm_layout)
        layout.add_widget(config_layout)
        self.check_btn = Button(
            text='Check Balance and Transfer', size_hint_y=None, height=60,
            background_color=(0, 0.7, 0, 1), background_down=(0, 0.5, 0, 1)
        )
        self.check_btn.bind(on_press=self.on_check)
        layout.add_widget(self.check_btn)
        results_layout = BoxLayout(orientation='vertical', size_hint=(1, 1))
        results_layout.add_widget(Label(text='Results:', size_hint_y=None, height=30))
        scroll = ScrollView()
        self.output_area = TextInput(
            readonly=True, size_hint_y=None, font_size=10,
            background_color=(0.12, 0.12, 0.12, 1),
            foreground_color=(1, 1, 1, 1), text=""
        )
        self.output_area.bind(minimum_height=self.output_area.setter('height'))
        scroll.add_widget(self.output_area)
        results_layout.add_widget(scroll)
        main_tab.add_widget(layout)
        return main_tab

    def create_settings_tab(self):
        settings_tab = TabbedPanelItem(text='Settings')
        layout = BoxLayout(orientation='vertical', padding=20, spacing=15)
        layout.add_widget(Label(
            text="Application Settings", font_size=18, bold=True, size_hint_y=None, height=40,
            color=(0, 0.7, 1, 1)
        ))
        save_layout = BoxLayout(orientation='horizontal', size_hint_y=None, height=40)
        save_layout.add_widget(Label(
            text="Save inputs on exit:", size_hint_x=0.7, halign='left'
        ))
        self.save_inputs_check = TextInput(
            text="✔" if self.config.get('save_inputs', True) else "",
            multiline=False, readonly=True, size_hint_x=0.3,
            background_color=(0.2, 0.2, 0.2, 1),
            foreground_color=(0, 1, 0, 1),
            font_size=20, halign='center'
        )
        self.save_inputs_check.bind(on_touch_down=self.toggle_save_inputs)
        save_layout.add_widget(self.save_inputs_check)
        layout.add_widget(save_layout)
        clear_btn = Button(
            text='Clear History and Results', size_hint_y=None, height=60,
            background_color=(0.8, 0.2, 0.2, 1)
        )
        clear_btn.bind(on_press=self.clear_history)
        layout.add_widget(clear_btn)
        info_text = (
            "Application Information:\n\n"
            "• Checks balances for Bitcoin, Ethereum, Litecoin, Dogecoin\n"
            "• Generates addresses from seed phrase\n"
            "• Supports seed phrase permutations\n"
            "• Automatic transfers to specified addresses\n"
            "• API key integration for better reliability\n\n"
            "WARNING: Storing seed phrases can be risky. Disable 'Save inputs' for security."
        )
        info_label = Label(
            text=info_text, size_hint_y=1, halign='left', valign='top'
        )
        info_label.bind(size=info_label.setter('text_size'))
        layout.add_widget(info_label)
        settings_tab.add_widget(layout)
        return settings_tab

    def create_api_tab(self):
        api_tab = TabbedPanelItem(text='API Keys')
        layout = BoxLayout(orientation='vertical', padding=10)
        layout.add_widget(Label(
            text="API Key Configuration", font_size=18, bold=True, size_hint_y=None, height=40,
            color=(0, 0.7, 1, 1)
        ))
        self.api_form = ApiKeyForm(self.config)
        layout.add_widget(self.api_form)
        status_layout = BoxLayout(orientation='vertical', size_hint_y=None, height=100, padding=10)
        status_layout.add_widget(Label(
            text="API Status:", size_hint_y=None, height=30, bold=True
        ))
        self.api_status = Label(
            text="Configure your API keys for better reliability",
            size_hint_y=None, height=70, halign='center', valign='middle'
        )
        self.api_status.bind(size=self.api_status.setter('text_size'))
        status_layout.add_widget(self.api_status)
        layout.add_widget(status_layout)
        api_tab.add_widget(layout)
        return api_tab

    def toggle_save_inputs(self, instance, touch):
        if instance.collide_point(*touch.pos):
            current = self.save_inputs_check.text == "✔"
            self.save_inputs_check.text = "" if current else "✔"
            self.config.set('save_inputs', not current)
            return True

    def clear_history(self, instance):
        self.output_area.text = ""
        self.config.set('last_seed', "")
        self.config.set('last_btc_addr', "")
        self.config.set('last_eth_addr', "")
        self.config.set('last_ltc_addr', "")
        self.config.set('last_doge_addr', "")
        self.seed_input.text = ""
        self.addr_input_bitcoin.text = ""
        self.addr_input_ethereum.text = ""
        self.addr_input_litecoin.text = ""
        self.addr_input_dogecoin.text = ""

    def on_seed_validate(self, instance):
        self.addr_input_bitcoin.focus = True
        self.on_check(instance)

    def on_check(self, instance):
        if self.worker and self.worker.is_alive():
            self.output_area.text = "Operation already in progress!\n"
            return
        self.output_area.text = ""  # Очистка перед новым запуском
        seed_phrase = self.seed_input.text.strip()
        if not seed_phrase:
            self.output_area.text = "Error: Seed phrase is required!\n"
            return
        if not validate_seed_phrase(seed_phrase):
            self.output_area.text = "Error: Invalid seed phrase format (BIP-39)!\n"
            return
        try:
            addr_count = int(self.addr_count_input.text)
            perm_count = int(self.perm_count_input.text)
            if addr_count < 1 or addr_count > 100:
                raise ValueError("Invalid address count")
            if perm_count < 1 or perm_count > 10:
                raise ValueError("Invalid permutation count")
        except ValueError:
            self.output_area.text = "Error: Invalid numeric values!\n"
            return
        target_addrs = {
            'Bitcoin': self.addr_input_bitcoin.text.strip(),
            'Ethereum': self.addr_input_ethereum.text.strip(),
            'Litecoin': self.addr_input_litecoin.text.strip(),
            'Dogecoin': self.addr_input_dogecoin.text.strip()
        }
        for coin, addr in target_addrs.items():
            if addr and not validate_address(coin, addr):
                self.output_area.text = f"Error: Invalid {coin} address: {addr}\n"
                return
        if self.save_inputs_check.text == "✔":
            self.config.set('last_seed', seed_phrase)
            self.config.set('last_btc_addr', target_addrs['Bitcoin'])
            self.config.set('last_eth_addr', target_addrs['Ethereum'])
            self.config.set('last_ltc_addr', target_addrs['Litecoin'])
            self.config.set('last_doge_addr', target_addrs['Dogecoin'])
            self.config.set('addr_count', addr_count)
            self.config.set('perm_count', perm_count)
        self.output_area.text = "Starting check...\n"
        self.output_area.text += f"Seed: {seed_phrase[:10]}...\n"
        self.output_area.text += f"Addresses: {addr_count}, Permutations: {perm_count}\n"
        self.output_area.text += "-" * 50 + "\n"
        self.progress_popup = ProgressPopup(cancel_callback=self.cancel_operation)
        self.progress_popup.open()
        self.update_api_status()
        args = (seed_phrase, addr_count, perm_count, target_addrs)
        self.worker = BalanceWorker(args, self.on_results, self.update_progress, self.config)
        self.worker.start()
        self.is_processing = True

    def update_progress(self, progress, status):
        Clock.schedule_once(lambda dt: self._update_progress(progress, status))

    def _update_progress(self, progress, status):
        if self.progress_popup:
            self.progress_popup.update_progress(progress, status)

    def cancel_operation(self):
        if self.worker and self.worker.is_alive():
            self.worker.stop_requested = True
            self.output_area.text += "Operation cancelled by user\n"
        if self.progress_popup:
            self.progress_popup.dismiss()
        self.is_processing = False

    def on_results(self, results, error):
        if self.progress_popup:
            self.progress_popup.dismiss()
        self.is_processing = False
        if error:
            self.output_area.text += f"\nError: {error}\n"
            return
        if not results:
            self.output_area.text += "\nNo funds found on checked addresses\n"
            return
        self.output_area.text += "\nResults:\n"
        self.output_area.text += "=" * 50 + "\n"
        total_value = 0
        for addr, data in results.items():
            result_text = (
                f"Coin: {data['coin']}\n"
                f"Address: {addr}\n"
                f"Balance: {data['balance']:.6f} {data['unit']}\n"
            )
            if 'tx_hash' in data:
                status = "SUCCESS" if data.get('transferred') else "FAILED"
                result_text += (
                    f"Transfer: {status}\n"
                    f"TX Hash: {data['tx_hash']}\n"
                )
            result_text += "-" * 50 + "\n"
            self.output_area.text += result_text
            total_value += data['balance'] * COIN_PRICES.get(data['coin'], 0)
        self.output_area.text += f"\nTotal Approximate Value: ${total_value:.2f} USD\n"
        self.output_area.text += "Results saved to crypto_hunter_results.json (private keys excluded)\n"

    def update_api_status(self):
        status_lines = []
        if self.config.get('infura_project_id'):
            status_lines.append("• Infura: Configured")
        else:
            status_lines.append("• Infura: Not configured (Ethereum requires Infura)")
        if self.config.get('etherscan_api_key'):
            status_lines.append("• Etherscan: Configured")
        else:
            status_lines.append("• Etherscan: Not configured (rate limits may apply)")
        if self.config.get('blockcypher_api_key'):
            status_lines.append("• BlockCypher: Configured")
        else:
            status_lines.append("• BlockCypher: Not configured (rate limits may apply)")
        self.api_status.text = "\n".join(status_lines)

    def on_stop(self):
        if self.save_inputs_check.text == "✔":
            self.config.set('last_seed', self.seed_input.text)
            self.config.set('last_btc_addr', self.addr_input_bitcoin.text)
            self.config.set('last_eth_addr', self.addr_input_ethereum.text)
            self.config.set('last_ltc_addr', self.addr_input_litecoin.text)
            self.config.set('last_doge_addr', self.addr_input_dogecoin.text)
            self.config.set('addr_count', self.addr_count_input.text)
            self.config.set('perm_count', self.perm_count_input.text)
