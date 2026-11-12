# Crypto Hunter (Kotlin / Jetpack Compose)

Нативная Android-версия приложения **Crypto Hunter** на Kotlin и Jetpack Compose.

## Что исправлено и улучшено по сравнению с Python/Kivy версией
- **Деривация ключей в памяти (BIP-39 / BIP-32 / BIP-44 / BIP-84)** через BouncyCastle без создания локальных SQLite-баз `bitcoinlib` и без ошибок инициализации цикла.
- **Корректный расчёт газа Ethereum**: комиссия (`gasPrice * 21000`) вычитается из баланса до формирования транзакции; добавлен резервный публичный RPC-узел, если Infura Project ID не задан.
- **Актуальные API**: проверка баланса BTC через `mempool.space`, проверка и отправка транзакций через `BlockCypher API` и `Ethereum JSON-RPC`.
- **Потокобезопасный UI на Jetpack Compose + Kotlin Coroutines**: прогресс-бар и лог результатов обновляются в реальном времени без зависаний и падений потока.
- **Шифрование настроек**: API-ключи и сохранённые поля защищены через `EncryptedSharedPreferences` (Android Keystore AES-256).

## Сборка APK в GitHub Actions
1. Загрузите файлы из этого архива в корень вашего репозитория GitHub.
2. Перейдите во вкладку **Actions** — workflow `Build APK` запустится автоматически при пуше в ветку `main` или `master` (также его можно запустить вручную кнопкой **Run workflow**).
3. После завершения сборки скачайте готовый пакет из раздела **Artifacts → CryptoHunter-debug-apk**.
