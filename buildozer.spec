[app]
title = Crypto Hunter
package.name = crypto_hunter
package.domain = org.example
source.dir = .
source.include_exts = py,png,json
version = 1.0
requirements = python3,kivy==2.2.1,requests==2.28.2,web3==6.30.0,bitcoinlib==0.6.13,cryptography==42.0.5,mnemonic==0.20,ratelimiter==0.2.0,tenacity==8.2.3
icon = icon.png
orientation = portrait
android.api = 33
android.minapi = 21
android.ndk = 25
android.ndk_path = /home/runner/android-sdk/ndk/25.0.8710880
android.private_storage = True
android.entry_point = main

[buildozer]
log_level = 2
warn_on_root = False
build_dir = ./build
bin_dir = ./bin
ccache = True
no_byte_code_compilation = False
