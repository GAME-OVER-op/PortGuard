# PortGuard refactor progress

## Stage 1 — daemon/firewall load stabilization — DONE

Implemented:
- firewall policy changes are based on effective DesiredRule changes, not listener PID/inode/process churn;
- listener diagnostic metadata can refresh without rebuilding iptables chains;
- cached working firewall backend is reused on policy updates;
- full capability probing is now a fallback/first-run path rather than the normal update path;
- periodic read-only firewall integrity reconciliation added (default 300 s, minimum 60 s);
- detector counter polling is time-throttled to at least 15 s and cannot tight-loop on xtables errors;
- default counter cadence raised to 5 daemon loops and network-local-address refresh reduced to 30 s;
- active firewall self-test is throttled to once per hour unless an integrity repair is required;
- counter reads are postponed after an apply/self-test pass to avoid command bursts;
- runtime state records last reconciliation time.

## Stage 2 — retry/detector/UDP/UID model — DONE

Implemented:
- ApplyReport now carries per-protocol failures and explicit completion state;
- failed protocol application is marked unsupported instead of looking healthy;
- daemon persists degraded state and retries with 5..300 s exponential backoff instead of hammering xtables;
- policy changes bypass retry backoff and are applied immediately;
- active self-test is suppressed for incomplete firewall state;
- detector correctly handles kernel counter resets after A/B chain replacement;
- obsolete counter baselines are pruned;
- grouped multiport hits are treated as ambiguous and no longer inflate exact unique-port count;
- destructive force-stop requires stronger evidence than a single ambiguous grouped hit;
- safer detector defaults / normalization for attempt and rule-hit thresholds;
- UDP sockets use stability aging, longer aging for ephemeral-port-range sockets, and a short removal grace period;
- PackageIndex supports one package across multiple Android user/profile UIDs;
- PackageManager index is augmented per Android user/work profile;
- system packages stay in the index so ignored-owner/package diagnostics keep working;
- third-party candidate UIDs are tracked separately rather than deleting system entries;
- currently running SDK sandbox / isolated UIDs are added to candidate UID knowledge.

## Stage 3 — Rust/Android state contract and UI safety — DONE

Implemented:
- daemon now atomically publishes status.json and summary.json expected by the app;
- incidents.jsonl is created and receives structured detector/reaction incidents;
- public runtime state carries apply-complete/degraded/error/retry information;
- Android runtime parser understands firewall_apply_complete and last_apply_error;
- MainHub reports firewall ERROR when backend is in degraded/incomplete state;
- tab.log firewall health treats active `ok` + disabled/idle protocols as healthy;
- blocked-port parser ignores the `... +N more` count instead of treating N as a port;
- all four protocols may now remain disabled; Rust no longer silently re-enables TCP4;
- Rust and Android default trusted package corrected to com.android.portguard;
- safer detector/counter/network defaults synchronized between backend and app;
- presets write the new reconciliation and UDP stabilization values;
- root atomic text writing no longer uses an injectable heredoc delimiter; content is strict shell-quoted data.

## Stage 4 — supervision, long-running autonomy, build safety and final verification — DONE

Implemented:
- `service.sh` is now a real lightweight supervisor with duplicate-instance protection, PID cleanup, 5..60 s restart backoff and stable-runtime reset;
- daemon stdout/stderr is drained through a FIFO log sink with live 1 MiB rotation (`daemon.log` + `.1`) without restarting the firewall service;
- legacy `deamon.log` is migrated once to the correctly named log;
- incidents journal is bounded/compacted at 1 MiB and detector cooldown/counter state is pruned;
- runtime state is loaded once and maintained in memory instead of reparsing `runtime_state.json` every discovery tick;
- unchanged network liveness timestamps no longer force a flash write every refresh interval;
- Android live polling is 15 s, serialized by a mutex, and no longer rereads config/trusted lists/root-manager data on every cycle;
- incident UI/diagnostics read only a bounded tail rather than the entire JSONL journal;
- custom `state_dir` is respected consistently by Main Hub, Dashboard, Incidents and Diagnostics;
- empty-but-existing `incidents.jsonl` is reported as an existing journal, not as a missing file;
- PackageManager enumeration is change/TTL driven (default 300 s) with 5..300 s failure backoff; transient SDK/isolated UIDs are refreshed cheaply from `/proc` every 15 s;
- app-zygote isolated UIDs (90000..98999) and regular isolated UIDs (99000..99999) are both included;
- the hot loop no longer clones the complete package index;
- module installer removes `/data/local/tmp/portguard_module.zip` on success, failure or cancellation;
- strict managed-chain removal detects real `-D`/`-F`/`-X` failures so stale DROP rules cannot be silently reported as cleared;
- hard xtables failures and standby-clear failures use 5..300 s exponential retry instead of falling back into the 2–3 s discovery loop;
- failed A/B protocol updates preserve known-old applied-rule state until retry succeeds;
- build script enforces Android AArch64 output, auto-selects `aarch64-linux-android` on non-Android hosts, detects the NDK linker and validates the final ELF architecture;
- Android/Rust UDP stabilization normalization is consistent (`ephemeral >= normal stabilization`).

## Effective steady-state load after refactor

Normal active hot loop:
- `/proc` socket discovery;
- in-memory package/policy comparison;
- no iptables mutation when effective policy is unchanged.

Periodic work:
- isolated/SDK transient UID `/proc` refresh: 15 s;
- package-source metadata check: 15 s (PackageManager itself only on change/TTL, default 300 s);
- rule counters: at least 30 s with force-stop reaction, at least 60 s in passive/warn-only operation;
- firewall integrity reconciliation: default 300 s;
- active self-test: at most once per hour unless integrity repair is required;
- Android live UI polling: 15 s, with bounded state reads.

Failure behavior:
- firewall apply/cleanup retry: 5, 10, 20, 40, 80, 160, then 300 s;
- PackageManager refresh retry: 5..300 s while retaining a previous valid cache;
- supervisor daemon restart: 5..60 s.

## Verification performed

Passed:
- `bash -n` for build/module scripts and `/bin/sh -n` for `service.sh`;
- XML parsing for all Android XML resources/manifests;
- TOML parsing for Cargo manifests;
- lexical/delimiter scan for all 75 Rust/Kotlin source files;
- no old injectable heredoc marker and no stale `com.example.portguardui` default;
- source-level invariants for counter/reconciliation/self-test cadence, in-memory runtime state and dynamic state directory use;
- supervisor simulation: restart/backoff, live log rotation, TERM shutdown, PID/FIFO cleanup;
- build-script simulation: non-Android host selects `aarch64-linux-android`; an explicit x86_64 target is rejected;
- Kotlin frontend parse pass over every modified Kotlin file found no parser/redeclaration diagnostics. The unresolved Android/Compose symbols are expected without the Gradle classpath;
- no generated `build/`, `.gradle/` or Rust `target/` directories are included in the source tree.

Environment-limited checks:
- full Rust `cargo check/test` could not run because this container does not contain cargo/rustc/rustfmt;
- full Android Gradle compilation could not start because the Gradle 8.2.1 wrapper distribution is not cached and this environment cannot reach `services.gradle.org` (`UnknownHostException`);
- therefore final device/CI compilation must still run in the normal project build environment. The source/static checks above are complete, but they are not a substitute for that compiler pass.

## Intentional item retained

`QUERY_ALL_PACKAGES` is still present because PortGuard's current all-app picker / UID policy needs visibility across installed applications. Removing it without redesigning that workflow would break functionality; distribution-policy work should be handled as a separate product change rather than disguised as a runtime bug fix.

## Post-final review — 2026-10-30

A second reliability/performance audit was completed after Stage 4. It adds protocol-diff firewall updates, policy-result caching, detector/counter timing alignment, strict stale-hook reconciliation, concurrent command-pipe draining, secondary-user force-stop correctness, supervisor orphan protection, Android config RMW serialization, and staging cleanup. See `PORTGUARD_REVIEW.md` for the detailed list and validation results.
