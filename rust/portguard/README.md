# PortGuard daemon (Rust)

Здесь находятся только исходники и Cargo-конфигурация daemon/backend части.

## Что вынесено из этой папки

Runtime-файлы модуля теперь лежат отдельно:

- `../../module_template/settings/`
- `../../module_template/etc/`
- `../../module_template/service.sh`
- `../../module_template/module.prop`

Это сделано, чтобы структура проекта была такой же, как у ZDT-D:
исходники отдельно, шаблон root-модуля отдельно, Android-приложение отдельно.

## Локальная сборка daemon

```bash
cargo build --manifest-path Cargo.toml --release
```
