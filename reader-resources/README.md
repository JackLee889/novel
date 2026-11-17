# Sherpa 阅读器资源

运行 scripts/prepare-reader-tts.ps1，从已校验的官方 1.13.8 SIMD Matcha zh-en 压缩包准备资源。
JS/WASM/DATA 全部来自同一个压缩包，不要用其他版本覆盖。

上游：https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8
运行时许可证：Sherpa-ONNX Apache-2.0，副本位于版本目录 LICENSE。
模型来源：https://k2-fsa.github.io/sherpa/onnx/tts/all/Chinese-English/matcha-icefall-zh-en.html
模型卡：https://modelscope.cn/models/dengcunqin/matcha_tts_zh_en_20251010
声码器：官方包中的 vocos-16khz-univ。发布时保留 README、许可证与清单。

目录同源映射到 /reader/tts/。正式部署只读挂载，配置 reader.tts.resource-directory。
大文件不打入 JAR。无需对全站设置 COOP/COEP。
