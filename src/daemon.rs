use crate::{
    config::{self, Config},
    detector,
    discovery,
    firewall,
    logger,
    policy,
    procfs,
    runtime::state::{DetectorState, RuntimeState},
    types::{
        FirewallProtoCapability, FirewallSelfTestResult, Listener, LocalAddressSnapshot, PackageIndex,
        Proto,
    },
};
use anyhow::Result;
use std::{
    fs,
    path::PathBuf,
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn runtime_state_path(cfg: &Config) -> PathBuf {
    PathBuf::from(&cfg.state_dir).join("runtime_state.json")
}

fn detector_state_path(cfg: &Config) -> PathBuf {
    PathBuf::from(&cfg.state_dir).join("detector_state.json")
}

fn firewall_caps_path(cfg: &Config) -> PathBuf {
    PathBuf::from(&cfg.state_dir).join("firewall_capabilities.json")
}

fn firewall_selftest_path(cfg: &Config) -> PathBuf {
    PathBuf::from(&cfg.state_dir).join("firewall_selftest.json")
}

fn protected_ports(listeners: &[Listener]) -> Vec<u16> {
    let mut ports: Vec<u16> = listeners.iter().map(|v| v.port).collect();
    ports.sort();
    ports.dedup();
    ports
}

#[derive(Debug, Clone, Default)]
struct PackageCache {
    loaded_at_secs: u64,
    index: PackageIndex,
}

impl PackageCache {
    fn get<'a>(&'a mut self, cfg: &Config) -> Result<&'a PackageIndex> {
        let now = now_secs();
        let expired = self.index.package_to_uid.is_empty()
            || now.saturating_sub(self.loaded_at_secs) >= cfg.package_refresh_secs.max(10);
        if expired {
            self.index = procfs::packages::load_package_index(cfg)?;
            self.loaded_at_secs = now;
        }
        Ok(&self.index)
    }

    fn invalidate(&mut self) {
        self.loaded_at_secs = 0;
        self.index = PackageIndex::default();
    }
}

#[derive(Debug, Clone, Default)]
struct NetworkMonitor {
    initialized: bool,
    last_checked_secs: u64,
    snapshots: Vec<LocalAddressSnapshot>,
}

impl NetworkMonitor {
    fn seed_from_runtime(&mut self, runtime: &RuntimeState) {
        if self.initialized {
            return;
        }
        self.initialized = true;
        self.last_checked_secs = runtime.last_network_check_epoch_secs;
        self.snapshots = runtime.local_address_snapshots.clone();
    }

    fn due(&self, now: u64, interval_secs: u64) -> bool {
        !self.initialized || self.last_checked_secs == 0 || now.saturating_sub(self.last_checked_secs) >= interval_secs.max(2)
    }

    fn remember(&mut self, now: u64, snapshots: Vec<LocalAddressSnapshot>) {
        self.initialized = true;
        self.last_checked_secs = now;
        self.snapshots = snapshots;
    }

    fn invalidate(&mut self) {
        self.initialized = false;
        self.last_checked_secs = 0;
        self.snapshots.clear();
    }
}

fn render_port_summary(cfg: &Config, ports: &[u16]) -> String {
    if ports.is_empty() {
        return "none".to_string();
    }
    let limit = cfg.summary_port_limit.max(1);
    let mut parts = ports
        .iter()
        .take(limit)
        .map(|v| v.to_string())
        .collect::<Vec<_>>();
    if ports.len() > limit {
        parts.push(format!("... +{} more", ports.len() - limit));
    }
    parts.join(",")
}

fn summarize_capabilities(capabilities: &[FirewallProtoCapability]) -> String {
    if capabilities.is_empty() {
        return "none".to_string();
    }

    capabilities
        .iter()
        .map(|cap| {
            if cap.rules_requested == 0 {
                if cap.supported {
                    return format!(
                        "{}:idle backend={}",
                        cap.proto.short_label(),
                        cap.selected_backend
                    );
                }
                if cap.last_error.is_empty() {
                    return format!("{}:idle unsupported", cap.proto.short_label());
                }
                return format!(
                    "{}:idle unsupported reason={}",
                    cap.proto.short_label(),
                    cap.last_error
                );
            }
            if cap.supported {
                format!(
                    "{}:ok requested_pairs={} grouped_rules={} backend={}",
                    cap.proto.short_label(),
                    cap.rules_requested,
                    cap.applied_rule_count,
                    cap.selected_backend
                )
            } else {
                format!(
                    "{}:unsupported requested_pairs={} reason={}",
                    cap.proto.short_label(),
                    cap.rules_requested,
                    cap.last_error
                )
            }
        })
        .collect::<Vec<_>>()
        .join("; ")
}

fn summarize_self_tests(results: &[FirewallSelfTestResult]) -> String {
    if results.is_empty() {
        return "none".to_string();
    }

    results
        .iter()
        .map(|item| {
            if !item.attempted {
                if item.error.is_empty() {
                    format!("{}:skipped", item.proto.short_label())
                } else {
                    format!("{}:skipped:{}", item.proto.short_label(), item.error)
                }
            } else if item.ok {
                format!(
                    "{}:ok packets={} target={}:{}",
                    item.proto.short_label(),
                    item.packets_after.saturating_sub(item.packets_before),
                    item.target_ip,
                    item.target_port
                )
            } else {
                format!("{}:failed:{}", item.proto.short_label(), item.error)
            }
        })
        .collect::<Vec<_>>()
        .join("; ")
}

fn summarize_local_snapshots(snapshots: &[LocalAddressSnapshot]) -> String {
    if snapshots.is_empty() {
        return "none".to_string();
    }

    snapshots
        .iter()
        .map(|item| {
            let preview = if item.addresses.is_empty() {
                "none".to_string()
            } else {
                item.addresses.iter().take(3).cloned().collect::<Vec<_>>().join(",")
            };
            let extra = item.addresses.len().saturating_sub(3);
            if extra > 0 {
                format!("{}=[{} ... +{}]", item.proto.short_label(), preview, extra)
            } else {
                format!("{}=[{}]", item.proto.short_label(), preview)
            }
        })
        .collect::<Vec<_>>()
        .join("; ")
}

fn save_firewall_capabilities(cfg: &Config, capabilities: &[FirewallProtoCapability]) -> Result<()> {
    let path = firewall_caps_path(cfg);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(&path, serde_json::to_string_pretty(capabilities)?)?;
    Ok(())
}

fn save_firewall_self_tests(cfg: &Config, results: &[FirewallSelfTestResult]) -> Result<()> {
    let path = firewall_selftest_path(cfg);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(&path, serde_json::to_string_pretty(results)?)?;
    Ok(())
}

fn clear_runtime_and_detector_state(cfg: &Config) -> Result<()> {
    let runtime_path = runtime_state_path(cfg);
    let detector_path = detector_state_path(cfg);
    let runtime = RuntimeState::default();
    runtime.save(&runtime_path)?;
    DetectorState::default().save(&detector_path)?;
    save_firewall_capabilities(cfg, &runtime.firewall_capabilities)?;
    save_firewall_self_tests(cfg, &runtime.firewall_self_tests)?;
    Ok(())
}

fn enter_standby(cfg: &Config, reason: &str) -> Result<()> {
    fs::create_dir_all(&cfg.state_dir)?;
    firewall::iptables::clear_chain(&cfg.chain_name)?;
    clear_runtime_and_detector_state(cfg)?;
    logger::info(
        "daemon",
        &format!(
            "active protection: off standby=true chain={} action=rules_cleared reason={} settings_dir={}",
            cfg.chain_name,
            reason,
            config::SETTINGS_DIR,
        ),
    );
    Ok(())
}

fn active_local_ip_list_protos(capabilities: &[FirewallProtoCapability]) -> Vec<Proto> {
    let mut protos = capabilities
        .iter()
        .filter(|cap| cap.supported && cap.applied_rule_count > 0 && cap.selected_backend.contains("local-ip-list("))
        .map(|cap| cap.proto)
        .collect::<Vec<_>>();
    protos.sort();
    protos.dedup();
    protos
}

fn local_ip_list_snapshots_for_protos(protos: &[Proto]) -> Vec<LocalAddressSnapshot> {
    firewall::iptables::current_local_address_snapshots(protos)
}

fn tick_active(
    cfg: &Config,
    package_cache: &mut PackageCache,
    network_monitor: &mut NetworkMonitor,
    loop_index: u64,
) -> Result<()> {
    fs::create_dir_all(&cfg.state_dir)?;

    let package_index = package_cache.get(cfg)?.clone();
    let listeners = discovery::listeners::discover(cfg, &package_index)?;
    let decision = policy::engine::build(cfg, &listeners, &package_index)?;

    let runtime_path = runtime_state_path(cfg);
    let mut runtime_state = RuntimeState::load_or_default(&runtime_path)?;
    network_monitor.seed_from_runtime(&runtime_state);

    let desired_ports = protected_ports(&decision.protected_listeners);
    let now = now_secs();
    let mut reapply_reasons = Vec::new();

    if runtime_state.desired_rules != decision.rules {
        reapply_reasons.push("desired_rules_changed".to_string());
    }
    if runtime_state.protected_listeners != decision.protected_listeners {
        reapply_reasons.push("protected_listeners_changed".to_string());
    }
    if runtime_state.trusted_client_uids != decision.trusted_client_uids {
        reapply_reasons.push("trusted_client_uids_changed".to_string());
    }
    if runtime_state.firewall_capabilities.is_empty() {
        reapply_reasons.push("firewall_capabilities_missing".to_string());
    }

    let local_ip_list_protos = active_local_ip_list_protos(&runtime_state.firewall_capabilities);
    if !local_ip_list_protos.is_empty() && network_monitor.due(now, cfg.network_refresh_secs) {
        let current_snapshots = local_ip_list_snapshots_for_protos(&local_ip_list_protos);
        if current_snapshots != network_monitor.snapshots {
            reapply_reasons.push(format!(
                "network_local_addresses_changed old={} new={}",
                summarize_local_snapshots(&network_monitor.snapshots),
                summarize_local_snapshots(&current_snapshots)
            ));
        }
        network_monitor.remember(now, current_snapshots);
        runtime_state.last_network_check_epoch_secs = now;
    }

    let changed = !reapply_reasons.is_empty();

    if changed {
        let mut report = firewall::iptables::apply_rules(&cfg.chain_name, &decision.rules)?;
        let self_tests = if cfg.active_self_test_enabled {
            firewall::iptables::run_active_self_tests(&mut report.capabilities, cfg.self_test_timeout_ms)
        } else {
            Vec::new()
        };
        let local_snapshots = local_ip_list_snapshots_for_protos(&active_local_ip_list_protos(&report.capabilities));
        network_monitor.remember(now, local_snapshots.clone());

        runtime_state.desired_rules = decision.rules.clone();
        runtime_state.protected_listeners = decision.protected_listeners.clone();
        runtime_state.protected_ports = desired_ports.clone();
        runtime_state.trusted_client_uids = decision.trusted_client_uids.clone();
        runtime_state.applied_rules = report.applied_rules.clone();
        runtime_state.backend = report.backend.clone();
        runtime_state.firewall_capabilities = report.capabilities.clone();
        runtime_state.firewall_self_tests = self_tests.clone();
        runtime_state.local_address_snapshots = local_snapshots.clone();
        runtime_state.last_apply_epoch_secs = now;
        runtime_state.last_self_test_epoch_secs = if self_tests.is_empty() { 0 } else { now };
        runtime_state.last_network_check_epoch_secs = now;
        runtime_state.save(&runtime_path)?;
        save_firewall_capabilities(cfg, &runtime_state.firewall_capabilities)?;
        save_firewall_self_tests(cfg, &runtime_state.firewall_self_tests)?;

        logger::info(
            "daemon",
            &format!(
                "active protection: on listeners={} ports={} grouped_rules={} candidate_apps={} backend={} blocked_ports=[{}] trusted_packages={} trusted_uids={} reaction_mode={} fw_caps={} self_test={} local_snapshots={} reapply_reason={} settings_dir={}",
                runtime_state.protected_listeners.len(),
                runtime_state.protected_ports.len(),
                report.actual_rule_count,
                decision.app_uids.len(),
                report.backend,
                render_port_summary(cfg, &runtime_state.protected_ports),
                cfg.trusted_client_packages.len(),
                cfg.trusted_client_uids.len(),
                cfg.reaction_mode,
                summarize_capabilities(&runtime_state.firewall_capabilities),
                summarize_self_tests(&runtime_state.firewall_self_tests),
                summarize_local_snapshots(&runtime_state.local_address_snapshots),
                reapply_reasons.join(" | "),
                config::SETTINGS_DIR,
            ),
        );
    }

    let should_refresh_counters = loop_index % cfg.counter_refresh_loops.max(1) == 0;
    if should_refresh_counters && !runtime_state.applied_rules.is_empty() {
        match firewall::iptables::read_counters(&runtime_state.applied_rules) {
            Ok(counters) => {
                let detector_path = detector_state_path(cfg);
                let mut detector_state = DetectorState::load_or_default(&detector_path)?;
                detector::scan::process_counters(cfg, &mut detector_state, &counters, &package_index);
                detector_state.save(&detector_path)?;
            }
            Err(err) => {
                logger::warn("daemon", &format!("counter refresh skipped: {err:#}"));
            }
        }
    }

    Ok(())
}

pub fn run(mut cfg: Config, once: bool) -> Result<()> {
    let mut package_cache = PackageCache::default();
    let mut network_monitor = NetworkMonitor::default();
    let mut loop_index = 0_u64;
    let mut last_settings_check = 0_u64;
    let mut standby_applied = false;

    loop {
        loop_index = loop_index.saturating_add(1);

        let now = now_secs();
        if last_settings_check == 0 || now.saturating_sub(last_settings_check) >= cfg.reload_check_secs.max(30) {
            last_settings_check = now;
            match config::load_module_settings() {
                Ok(new_cfg) => {
                    if new_cfg != cfg {
                        logger::info(
                            "daemon",
                            &format!(
                                "settings reloaded: active_protection={} tcp4={} udp4={} tcp6={} udp6={} reaction_mode={} active_self_test={} self_test_timeout_ms={} trusted_packages={} trusted_uids={} kill_exceptions={} scan_ignores={} reload_check_secs={} network_refresh_secs={} settings_dir={}",
                                new_cfg.active_protection,
                                new_cfg.tcp4_enabled,
                                new_cfg.udp4_enabled,
                                new_cfg.tcp6_enabled,
                                new_cfg.udp6_enabled,
                                new_cfg.reaction_mode,
                                new_cfg.active_self_test_enabled,
                                new_cfg.self_test_timeout_ms,
                                new_cfg.trusted_client_packages.len(),
                                new_cfg.trusted_client_uids.len(),
                                new_cfg.kill_exceptions_packages.len(),
                                new_cfg.scan_ignore_packages.len(),
                                new_cfg.reload_check_secs,
                                new_cfg.network_refresh_secs,
                                config::SETTINGS_DIR,
                            ),
                        );
                        cfg = new_cfg;
                        package_cache.invalidate();
                        network_monitor.invalidate();
                        standby_applied = false;
                    }
                }
                Err(err) => {
                    logger::warn("daemon", &format!("settings reload skipped: {err:#}"));
                }
            }
        }

        if !cfg.active_protection_enabled() {
            if !standby_applied {
                if let Err(err) = enter_standby(&cfg, "config active_protection=off") {
                    logger::error("daemon", &format!("standby enter failed: {err:#}"));
                } else {
                    standby_applied = true;
                    network_monitor.invalidate();
                }
            }
        } else {
            standby_applied = false;
            if let Err(err) = tick_active(&cfg, &mut package_cache, &mut network_monitor, loop_index) {
                logger::error("daemon", &format!("tick failed: {err:#}"));
            }
        }

        if once {
            break;
        }

        thread::sleep(Duration::from_millis(cfg.loop_interval_ms.max(500)));
    }
    Ok(())
}
