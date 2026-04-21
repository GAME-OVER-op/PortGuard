# PortGuard

PortGuard приведён к структуре проекта в стиле ZDT-D, чтобы удобнее было обновлять Android-приложение, Rust daemon и root-модуль независимо.

## Структура

```text
.
├── application/              # Android app
├── rust/portguard/           # Rust daemon/backend
├── module_template/          # шаблон root-модуля
├── prebuilt/bin/arm64-v8a/   # место под внешние arm64 бинарники
├── module.prop               # единая версия проекта/модуля
├── build.sh                  # общий сборщик
├── Makefile
└── out/                      # артефакты сборки
```

## Что изменено по сравнению с исходным архивом

- бывшая папка `bin/` перенесена в `rust/portguard/`
- структура root-модуля вынесена в `module_template/` из встроенного `module.zip`
- runtime defaults (`settings/`, `etc/`) вынесены из Rust-части в `module_template/`
- `module.prop` поднят в корень и стал единым источником версии
- Android assets `module.zip` и `module.prop` теперь считаются генерируемыми
- добавлен общий `build.sh`, который собирает daemon, модуль и APK

## Основные команды

```bash
./build.sh doctor
./build.sh module
./build.sh apk
./build.sh clean
```
