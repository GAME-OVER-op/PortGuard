# PortGuard Stage 7

PortGuard — root-демон на Rust для Android/Linux, который динамически защищает локальные listening TCP-порты от обычных приложений, отслеживает подозрительные попытки probing/scan и теперь читает настройки **только** из жёстко зашитого пути модуля:

```text
/data/adb/modules/PortGuard/settings/
```

## Что добавлено в Stage 7

- жёстко зашитый путь настроек модуля: `/data/adb/modules/PortGuard/settings/`;
- автоматическая подгрузка настроек без перезапуска;
- периодическая проверка изменений настроек каждые `30` секунд;
- гибридная схема настроек:
  - `config.json` — основные режимы;
  - `trusted_packages.txt` — приложения, которым разрешён localhost-доступ;
  - `trusted_uids.txt` — UID, которым разрешён localhost-доступ;
  - `kill_exceptions.txt` — приложения, которые нельзя force-stop;
  - `scan_ignore_packages.txt` — пакеты, события которых игнорируются как инцидент;
- новая переменная `active_protection=on|off`;
- при `active_protection=off` демон:
  - удаляет свои правила и hook из `iptables`;
  - очищает свой runtime/detector state;
  - переходит в режим ожидания;
  - продолжает только ждать изменения настроек;
- режим реакции `reaction_mode=off|force_stop`;
- безопасный `force-stop` только для обычных app UID и только после срабатывания детектора;
- `force-stop` не применяется к:
  - trusted packages;
  - trusted UIDs;
  - пакетам из `kill_exceptions.txt`;
  - пакетам из `scan_ignore_packages.txt`;
  - system/root/shell UID;
- `resolve_process_details=false` по умолчанию, чтобы не долбить `/proc/*/fd` без необходимости.

## Где редактировать настройки

На устройстве PortGuard будет читать только эти файлы:

```text
/data/adb/modules/PortGuard/settings/config.json
/data/adb/modules/PortGuard/settings/trusted_packages.txt
/data/adb/modules/PortGuard/settings/trusted_uids.txt
/data/adb/modules/PortGuard/settings/kill_exceptions.txt
/data/adb/modules/PortGuard/settings/scan_ignore_packages.txt
```

В проекте лежит папка `settings/` с примером содержимого этих файлов.

## Важные поля `config.json`

- `active_protection`: `on` или `off`
- `reaction_mode`: `off` или `force_stop`
- `reload_check_secs`: интервал автоперечитки настроек; по умолчанию `30`
- `learning_mode`: если `true`, детектор только логирует и никого не force-stop
- `resolve_process_details`: если `true`, демон будет пытаться привязать сокеты к PID/cmdline через `/proc/*/fd`
- `counter_refresh_loops`: как часто читать счётчики относительно основного цикла

## Как работает dynamic reload

- демон стартует и загружает настройки из жёстко заданного пути;
- каждые 30 секунд перечитывает настройки;
- если новый набор настроек отличается от текущего, он автоматически применяется без перезапуска;
- если `config.json` битый или файл временно некорректен, текущая рабочая конфигурация сохраняется, а в консоль пишется `settings reload skipped`;
- если `active_protection=off`, демон сразу удаляет свои правила и остаётся в режиме ожидания.

## Запуск

```bash
cargo build --release
```

```bash
./target/release/portguard
```

или один цикл:

```bash
./target/release/portguard --once
```

На Android в модуле:

```sh
/data/adb/modules/PortGuard/bin/portguard
```

## Логи

Активный режим:

```text
[ts] [INFO] [daemon] active protection: on ports=3 rules=263 backend=local=-o lo, ports=multiport blocked_ports=[1004,1006,1080] trusted_packages=2 trusted_uids=1 reaction_mode=off settings_dir=/data/adb/modules/PortGuard/settings
```

Режим ожидания:

```text
[ts] [INFO] [daemon] active protection: off standby=true chain=PORTGUARD action=rules_cleared reason=config active_protection=off settings_dir=/data/adb/modules/PortGuard/settings
```

Инцидент:

```text
[ts] [WARN] [detector] possible localhost scan detected: uid=10261 packages=com.example.scanner unique_ports=2 exact_ports=0 total_attempts=2 rule_hits=1 grouped_hits=1 window=10s ports=[1004,1006] reaction_mode=force_stop
```

## Ограничения текущей стадии

- пока только TCP IPv4;
- пока backend только `iptables`;
- force-stop делается через `am force-stop <package>`;
- если у UID нет нормального package name, реакция не применяется;
- настройки читаются только из модуля, опции `--config` нет.
