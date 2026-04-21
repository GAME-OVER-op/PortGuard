# PortGuard App

Android-приложение PortGuard теперь находится внутри общей корневой структуры проекта.

## Что лежит рядом с приложением

- `../module.prop` — единая версия проекта и модуля
- `../module_template/` — файловая структура root-модуля
- `../rust/portguard/` — исходники daemon/backend на Rust
- `app/src/main/assets/module.zip` и `module.prop` — генерируются корневым `build.sh`

## Сборка

Рекомендуемый способ — из корня репозитория:

```bash
./build.sh apk
```

Это:
- соберёт Rust binary `portguard`
- упакует `module_template/` в `out/module/PortGuard_module.zip`
- встроит `module.zip` и `module.prop` в Android assets
- соберёт APK и положит артефакты в `out/dist/`

Прямая Gradle-сборка тоже возможна:

```bash
cd application
./gradlew assembleDebug
```

Но в этом случае встроенный ZIP модуля должен уже быть подготовлен заранее.
