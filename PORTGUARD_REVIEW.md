# PortGuard — post-final reliability/performance review

Date: 2026-10-30
Baseline: previous `PortGuard-final.zip`

This pass intentionally changes only behavior that materially improves runtime cost, correctness, or recovery. No new product features or architecture were added.

## Changes made

### 1. Detector timing now matches real counter sampling
- `scan_window_secs` defaults to 60 seconds.
- Rust and Android both derive the minimum detector window from the real counter cadence.
- Force-stop mode keeps the 30-second minimum; passive/off mode uses at least 60 seconds.
- This fixes the previous mismatch where counters could be sampled every 30–60 seconds while the detector discarded events after 10–15 seconds.

### 2. Policy build no longer burns CPU/memory every loop
- Added a policy cache keyed by normalized config, package-index revision, and semantic listener identity.
- PID/inode/process-name churn refreshes diagnostics without rebuilding the listener x UID rule Cartesian product.
- Deterministic policy-build failures are cached for unchanged inputs.
- `max_rules` is enforced while rules are inserted, so construction stops immediately instead of allocating the full oversized policy first.
- A manually configured `max_rules` value is still respected by Rust; no unnecessary hard minimum was introduced in the daemon.

### 3. Incremental firewall updates
- A normal listener-policy change now rebuilds only the TCP4/UDP4/TCP6/UDP6 protocol whose desired rules changed.
- Unchanged protocol chains keep their current A/B slot and counters.
- Full reapply remains mandatory for integrity loss, network-address change, incomplete apply, missing capabilities, and recovery paths.
- This materially reduces xtables process launches and counter resets during normal operation.

### 4. Stronger A/B-chain reconciliation
- Old/legacy hooks are no longer removed on a best-effort basis after a switch; cleanup failure marks the apply degraded and schedules retry.
- Reconciliation verifies both the expected active hook and absence of stale legacy/other A/B hooks.
- Empty desired policy is also checked for stale PortGuard OUTPUT hooks.
- OUTPUT listing is reused per address family instead of being requested repeatedly.
- Hook removal deletes the complete snapshot before rereading OUTPUT, reducing repeated `iptables -S OUTPUT` calls.

### 5. Safer capability/self-test cleanup
- Probe/self-test rules are deleted by their exact rule specification rather than by line number.
- Periodic active self-test now actually runs on a stable firewall (at most hourly) without forcing a policy reapply.
- A failed self-test schedules an early structural reconciliation.

### 6. xtables command pipe deadlock fixed
- Rust now drains stdout and stderr concurrently while a command is running.
- This prevents `iptables -S/-L` or other verbose commands from blocking when a pipe buffer fills while the daemon waits for process exit.
- Timeout/error paths kill and reap the command before returning.

### 7. `/proc` hot-path reductions
- Socket-table parsing avoids per-row `Vec` allocation and skips malformed/transient rows instead of aborting a full table.
- IPv4/IPv6 decoding avoids temporary byte vectors.
- Process inode resolution exits as soon as all requested socket inodes have been found.

### 8. Android settings write races fixed
- Config read-modify-write operations are process-wide serialized across repository/ViewModel instances.
- Rapid dashboard toggles save the latest UI state instead of stale coroutine snapshots.
- Malformed JSON is treated as an error rather than silently becoming a factory-default object.
- Imported configuration is validated before overwrite.

### 9. Root shell robustness
- The persistent-root completion marker is unique per command.
- Root output is not recopied/rescanned on every 10 ms poll when no new bytes arrived.
- Atomic file writes no longer use an injectable heredoc delimiter.
- Temporary root files are cleaned when write/move fails.

### 10. Secondary-user/work-profile reaction correctness
- `force-stop` now classifies Android applications by appId, not absolute UID.
- System UIDs in secondary users are not mistaken for normal applications.
- `am force-stop --user <userId>` targets the correct Android profile.
- Reaction cooldown is keyed by `userId:package`.

### 11. Supervisor hardening
- A stale `portguard.pid` left after supervisor SIGKILL is checked and a confirmed orphan PortGuard daemon is stopped before a new daemon starts.
- Normal supervisor shutdown waits for the tracked daemon to exit and escalates only while the PID still belongs to PortGuard.
- Prevents two independent daemons from simultaneously modifying the same iptables chains.

### 12. Module ZIP staging cleanup
- App cache staging uses a unique temporary file, avoiding install/export races over one fixed cache filename.
- A failed copy to `/data/local/tmp/portguard_module.zip` removes the partial destination.
- Cache and root staging files are cleaned on success, failure, and cancellation paths.

### 13. Smaller Android/package work
- Installed-app catalog no longer makes a duplicate PackageManager pass.
- Incident clearing truncates the existing inode rather than replacing it while Rust may append concurrently.
- JSON diagnostic files are written only when content changes.

## Validation performed

- `bash -n` and `sh -n` on build/module scripts: PASS.
- TOML parse for all Cargo TOML files: PASS.
- XML parse for all project XML files: PASS.
- Structural delimiter scan across Rust/Kotlin/KTS sources: PASS.
- Targeted `kotlinc` parser pass over every Kotlin file modified in this review: no syntax/parser diagnostics. Compilation itself cannot succeed without Android/coroutines/org.json classpath.
- Supervisor TERM test with a daemon deliberately ignoring TERM: PASS; daemon was not left running and PID/FIFO files were removed.
- Supervisor stale-orphan test: PASS; orphan was stopped and stale PID files were removed.
- Unsafe legacy pattern scan (`__PG_EOF__`, old `com.example.portguardui`): clean.
- No build/target/cache artifacts were added to the project.

## Environment limitation

A complete compiler/build verification cannot be honestly claimed in this environment. `rustc`/`cargo` are not installed. The Android build also lacks the usable Android/Gradle dependency environment needed for a real Gradle compile. The changes were therefore validated by static parsing, targeted Kotlin parser diagnostics, shell simulations, contract checks, and code-path review. A normal project CI/device build remains the final compiler/runtime gate.
