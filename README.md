# PortGuard Stage 12

PortGuard — root-демон на Rust для Android/Linux, который динамически защищает локальные сокеты приложений от чужих app UID, отслеживает подозрительные probing/scan-попытки и читает настройки только из жёстко заданного пути модуля:

```text
/data/adb/modules/PortGuard/settings/
```

## Что добавлено в Stage 12

- поддержка `TCP IPv4`, `UDP IPv4`, `TCP IPv6`, `UDP IPv6`;
- discovery теперь читает:
  - `/proc/net/tcp`
  - `/proc/net/udp`
  - `/proc/net/tcp6`
  - `/proc/net/udp6`
- firewall теперь умеет работать и через `iptables`, и через `ip6tables`;
- правила разделены по семейству и протоколу в отдельные chain-слоты с безопасным переключением:
  - `PORTGUARD_4T_A` / `PORTGUARD_4T_B`
  - `PORTGUARD_4U_A` / `PORTGUARD_4U_B`
  - `PORTGUARD_6T_A` / `PORTGUARD_6T_B`
  - `PORTGUARD_6U_A` / `PORTGUARD_6U_B`
- backend подбирается отдельно для каждого из `tcp4 / udp4 / tcp6 / udp6`;
- fallback для локального трафика теперь есть и для IPv6 (`::1`, `lo`, `dst-type LOCAL`);
- если `dst-type LOCAL` недоступен, PortGuard умеет пробовать список реально назначенных локальных IP-адресов устройства;
- detector логирует протокол и семейство, а не только UID и порты;
- detector анализирует thresholds отдельно по каждому `tcp4/udp4/tcp6/udp6`, чтобы события разных стеков не смешивались;
- detector теперь использует более строгие пороги для UDP, чтобы шумные UDP-события не давали лишние false positive, но при этом сохраняли обнаружение реального multi-port probing;
- добавлен `firewall_capabilities.json` с отчётом о поддержке backend на устройстве;
- если один backend отсутствует или сломан, демон сохраняет рабочие правила для остальных протоколов вместо полного отказа защиты;
- UDP discovery стал строже: для защиты берутся только локально bound/unconnected UDP-сокеты, а не любые UDP-записи из `/proc/net/udp*`;
- `protect_loopback_only` теперь корректно учитывает wildcard bind (`0.0.0.0` и `::`), которые тоже доступны с loopback;
- комментарии iptables-правил сокращены и стабилизированы, чтобы не упираться в лимит длины comment-match;
- если устройство поддерживает `-m owner --socket-exists`, PortGuard использует его как дополнительную страховку;
- поиск `iptables` и `ip6tables` теперь сначала идёт по типичным Android-путям (`/system/bin/...`, `/system/xbin/...`, `/vendor/bin/...`), а затем уже по `PATH`;
- при пустом наборе правил для конкретного протокола соответствующие chain-слоты очищаются отдельно, без полного сброса всей защиты;
- применение правил стало безопаснее: новый набор сначала собирается во временный slot-chain, и только потом старый hook снимается;
- после успешного применения правил демон выполняет активный self-test по каждому рабочему протоколу: временно ставит self-test rule в активную chain, генерирует локальный TCP/UDP трафик на loopback и проверяет рост счётчика;
- результат self-test сохраняется и в `runtime_state.json`, и в отдельный файл `firewall_selftest.json`;
- sample `config.json` теперь содержит отдельные переключатели:
  - `tcp4_enabled`
  - `udp4_enabled`
  - `tcp6_enabled`
  - `udp6_enabled`

## Где редактировать настройки

На устройстве PortGuard читает только эти файлы:

```text
/data/adb/modules/PortGuard/settings/config.json
/data/adb/modules/PortGuard/settings/trusted_packages.txt
/data/adb/modules/PortGuard/settings/trusted_uids.txt
/data/adb/modules/PortGuard/settings/kill_exceptions.txt
/data/adb/modules/PortGuard/settings/scan_ignore_packages.txt
```

## Важные поля `config.json`

- `active_protection`: `on` или `off`
- `reaction_mode`: `off` или `force_stop`
- `protect_loopback_only`: ограничить защиту только loopback-адресами
- `user_apps_only`: применять защиту только к пользовательским/third-party приложениям и не строить правила для предустановленных system/priv-app
- `tcp4_enabled`: включить защиту TCP IPv4
- `udp4_enabled`: включить защиту UDP IPv4
- `tcp6_enabled`: включить защиту TCP IPv6
- `udp6_enabled`: включить защиту UDP IPv6
- `learning_mode`: только логировать, не делать `force-stop`
- `resolve_process_details`: пытаться привязывать inode к PID/cmdline через `/proc/*/fd`
- `counter_refresh_loops`: как часто читать счётчики относительно основного цикла
- `active_self_test_enabled`: запускать активный firewall self-test после reapply
- `self_test_timeout_ms`: таймаут команды генерации self-test трафика

Если все четыре переключателя протоколов случайно выключены, PortGuard автоматически включает `tcp4_enabled=true`, чтобы демон не ушёл в полностью пустое состояние по ошибке.

## Как работает firewall backend

Для каждого типа трафика PortGuard отдельно подбирает рабочую комбинацию:

- binary:
  - `iptables` для IPv4
  - `ip6tables` для IPv6
- local match fallback:
  - `-m addrtype --dst-type LOCAL`
  - список реально назначенных локальных IP-адресов устройства
  - `-o lo`
  - `-d 127.0.0.1` или `-d ::1`
  - `-o lo -d 127.0.0.1` или `-o lo -d ::1`
- port match fallback:
  - `multiport`
  - `per-port`

Это нужно для устройств, где отдельные xtables-match'и работают нестабильно или собраны не одинаково. Если kernel поддерживает `--socket-exists`, backend будет использовать и его. После применения PortGuard сохраняет подробный capability report в:

```text
/data/adb/modules/PortGuard/settings/state/firewall_capabilities.json
```

А результат активного self-test сохраняется в:

```text
/data/adb/modules/PortGuard/settings/state/firewall_selftest.json
```

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

## Примеры логов

Активация защиты:

```text
[ts] [INFO] [daemon] active protection: on listeners=12 ports=7 rules=611 backend=tcp4[bin=iptables local=dst-type LOCAL ports=multiport]; udp4[bin=iptables local=-o lo ports=multiport]; tcp6[bin=ip6tables local=dst-type LOCAL ports=multiport] blocked_ports=[53,80,443,5353,8080,9000,1080] trusted_packages=2 trusted_uids=1 reaction_mode=off fw_caps=tcp4:ok rules=120 applied=12 backend=... self_test=tcp4:ok packets=1 target=127.0.0.1:45940; udp4:ok packets=1 target=127.0.0.1:45941 settings_dir=/data/adb/modules/PortGuard/settings
```

Падение одного backend-варианта и fallback:

```text
[ts] [WARN] [firewall] backend attempt failed proto=tcp6 chain=PORTGUARD_6T bin=ip6tables local=dst-type LOCAL ports=multiport => ip6tables add rule failed (...)
[ts] [INFO] [firewall] selected backend proto=tcp6 chain=PORTGUARD_6T bin=ip6tables local=-o lo ports=multiport
```

Инцидент:

```text
[ts] [WARN] [detector] possible localhost scan detected: uid=10261 packages=com.example.scanner proto=udp6 unique_ports=4 exact_ports=3 total_attempts=5 rule_hits=2 grouped_hits=2 window=10s targets=udp6:[5353,5354,5355,5356] reaction_mode=force_stop
[ts] [INFO] [detector] thresholds proto=udp6 unique_ports>=3 attempts>=4 rule_hits>=2 attempts_need_ports>=2 attempts_need_grouped_hits>=2
```

## Ограничения этой стадии

- надёжность всё ещё зависит от того, какие именно xtables-модули реально собраны в ядре устройства;
- UDP discovery в Linux/Android остаётся более чувствительным к особенностям `/proc/net/udp*`, чем TCP, хотя в этой стадии он уже отсеивает connected client sockets по `rem_address`;
- активный self-test запускается от UID демона и подтверждает, что hook + owner + local-match реально ловят локальный трафик на устройстве, но не заменяет проверку конкретного чужого app UID на живом телефоне.


## Network change handling

When a backend falls back to `local-ip-list(...)`, PortGuard now tracks the current local IPv4/IPv6 addresses and automatically reapplies rules when the address list changes, for example after Wi-Fi, mobile data, VPN, or IPv6 changes. The check interval is controlled by `network_refresh_secs` in `settings/config.json`.


## User apps only

PortGuard now filters package discovery through `cmd package list packages -U -3` (with `pm` fallback) when `user_apps_only=true`. This keeps candidate client UIDs and protected listener owners focused on third-party user apps instead of preinstalled system or priv-app packages.
