# Zenz ランタイムのホスト側テスト

Android 端末なしで、`libmozkey_zenz.so` に入るのと同じ C++ コードを PC 上で動かす
テストです。固定コミットの llama.cpp と、リポジトリ内の Zenz モデルを使います。
APK のビルドには含まれません。

- `backend_test`: llama.cpp バックエンド単体。変換結果、KV キャッシュの再利用、
  タイムアウト、キャンセル、中断後の復帰、出力バッファ不足を確認します。
- `client_test`: `ZenzInProcessClient`(libmozc.so 側)→ C ABI → バックエンドの
  一連の経路。実行中の登録解除も確認します。Abseil のソースが必要です。

```
android/scripts/prepare_native.sh llama
cmake -S android/tools/host_tests -B /tmp/mozkey-host-tests -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DMOZKEY_ABSL_DIR=/path/to/abseil-cpp
cmake --build /tmp/mozkey-host-tests
MODEL=src/win32/installer/zenz_runtime/models/zenz-v3.2-small-Q5_K_M.gguf
/tmp/mozkey-host-tests/backend_test "$MODEL"
/tmp/mozkey-host-tests/client_test "$MODEL"
```

`-DMOZKEY_ABSL_DIR` を省くと `backend_test` だけをビルドします。どちらも最後に
`failures=0` と表示されれば成功です。

`ZenzInProcessClient` 単体のテスト(偽のバックエンドを使うもの)は
`src/session/zenz_inprocess_client_test.cc` にあり、Bazel で実行します。
