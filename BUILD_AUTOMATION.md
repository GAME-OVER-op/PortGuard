# PortGuard build automation

## Режимы

```bash
./build.sh doctor   # проверить окружение
./build.sh module   # собрать Rust binary + ZIP модуля
./build.sh apk      # собрать ZIP модуля + APK
./build.sh clean    # очистить out/ и generated assets
./build.sh clear    # clean + rust target + app build
```

## Что делает build.sh

1. Проверяет наличие `cargo`
2. Собирает `rust/portguard/` в release
3. Копирует `module_template/` во временную папку
4. Подкладывает туда `module.prop` и `bin/portguard`
5. Упаковывает модуль в `out/module/PortGuard_module.zip`
6. Копирует `module.zip` и `module.prop` в `application/app/src/main/assets/`
7. При режиме `apk` запускает Gradle-сборку Android-приложения

## Зависимости

- Rust toolchain (`cargo`, `rustc`)
- JDK 17
- Android SDK для Gradle-сборки APK
