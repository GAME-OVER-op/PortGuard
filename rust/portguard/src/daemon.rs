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
    cmp,
    fs,
    path::PathBuf,
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};


const TAB_LOG_PATH: &str = "/data/adb/modules/PortGuard/log/tab.log";
const TAB_LOG_MIN_WRITE_SECS: u64 = 5;

#[derive(Debug, Clone, Default)]
struct DashboardWriter {
    last_written_secs: u64,
    last_written_content: String,
    pending_content: Option<String>,
}

impl DashboardWriter {
    fn update(&mut self, text: String, now: u64) -> Result<()> {
        if text == self.last_written_content {
            self.pending_content = None;
            return Ok(());
        }
        if self.pending_content.as_ref() != Some(&text) {
            self.pending_content = Some(text);
        }
        self.flush_if_due(now)
    }

    fn flush_if_due(&mut self, now: u64) -> Result<()> {
        let Some(text) = self.pending_content.clone() else {
            return Ok(());
        };
        if self.last_written_secs != 0 && now.saturating_sub(self.last_written_secs) < TAB_LOG_MIN_WRITE_SECS {
            return Ok(());
        }
        let path = PathBuf::from(TAB_LOG_PATH);
        let tmp = path.with_extension("tmp");
        fs::write(&tmp, &text)?;
        fs::rename(&tmp, &path)?;
        self.last_written_secs = now;
        self.last_written_content = text;
        self.pending_content = None;
        Ok(())
    }
}

#[derive(Debug, Clone, Default)]
struct DashboardSnapshot {
    status: String,
    listeners: usize,
    ports: usize,
    grouped_rules: usize,
    candidate_apps: usize,
    blocked_ports: String,
    backend: String,
    fw_caps: String,
    self_test: String,
    local_snapshots: String,
    reapply_reason: String,
    protection_scope: String,
    trusted_packages: usize,
    trusted_uids: usize,
    reaction_mode: String,
    loop_interval_ms: u64,
    reload_check_secs: u64,
    network_refresh_secs: u64,
    package_refresh_secs: u64,
    counter_refresh_loops: u64,
    updated_at_secs: u64,
}

fn table_border(width: usize) -> String {
    format!("+{}+", "-".repeat(width + 2))
}

fn table_row(label_width: usize, label: &str, value: &str) -> String {
    format!("| {:label_width$} : {} |", label, value, label_width = label_width)
}

fn render_dashboard(snapshot: &DashboardSnapshot) -> String {
    let rows = vec![
        ("updated_at", snapshot.updated_at_secs.to_string()),
        ("status", snapshot.status.clone()),
        ("listeners", snapshot.listeners.to_string()),
        ("ports", snapshot.ports.to_string()),
        ("grouped_rules", snapshot.grouped_rules.to_string()),
        ("candidate_apps", snapshot.candidate_apps.to_string()),
        ("blocked_ports", snapshot.blocked_ports.clone()),
        ("protection_scope", snapshot.protection_scope.clone()),
        ("backend", snapshot.backend.clone()),
        ("fw_caps", snapshot.fw_caps.clone()),
        ("self_test", snapshot.self_test.clone()),
        ("local_snapshots", snapshot.local_snapshots.clone()),
        ("reapply_reason", snapshot.reapply_reason.clone()),
        ("trusted_packages", snapshot.trusted_packages.to_string()),
        ("trusted_uids", snapshot.trusted_uids.to_string()),
        ("reaction_mode", snapshot.reaction_mode.clone()),
        ("loop_interval_ms", snapshot.loop_interval_ms.to_string()),
        ("reload_check_secs", snapshot.reload_check_secs.to_string()),
        ("network_refresh_secs", snapshot.network_refresh_secs.to_string()),
        ("package_refresh_secs", snapshot.package_refresh_secs.to_string()),
        ("counter_refresh_loops", snapshot.counter_refresh_loops.to_string()),
    ];

    let label_width = rows.iter().map(|(k, _)| k.len()).max().unwrap_or(0);
    let content_width = rows
        .iter()
        .map(|(k, v)| label_width + 3 + v.len())
        .max()
        .unwrap_or(0);
    let title = "PortGuard live status";
    let width = cmp::max(content_width, title.len());
    let mut out = String::new();
    out.push_str(&table_border(width));
    out.push('\n');
    out.push_str(&format!("| {:width$} |", title, width = width));
    out.push('\n');
    out.push_str(&table_border(width));
    out.push('\n');
    for (idx, (label, value)) in rows.iter().enumerate() {
        out.push_str(&table_row(label_width, label, value));
        if idx + 1 != rows.len() {
            out.push('\n');
        }
    }
    out.push('\n');
    out.push_str(&table_border(width));
    out.push('\n');
    out
}

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

fn enter_standby(cfg: &Config, reason: &str, dashboard: &mut DashboardWriter) -> Result<()> {
    fs::create_dir_all(&cfg.state_dir)?;
    firewall::iptables::clear_chain(&cfg.chain_name)?;
    clear_runtime_and_detector_state(cfg)?;
    let now = now_secs();
    let snapshot = dashboard_snapshot_standby(cfg, reason, now);
    dashboard.update(render_dashboard(&snapshot), now)?;
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

fn dashboard_snapshot_active(
    cfg: &Config,
    runtime_state: &RuntimeState,
    candidate_apps: usize,
    grouped_rules: usize,
    reapply_reason: &str,
    state_updated_at_secs: u64,
) -> DashboardSnapshot {
    DashboardSnapshot {
        status: if cfg.active_protection_enabled() { "on".to_string() } else { "off".to_string() },
        listeners: runtime_state.protected_listeners.len(),
        ports: runtime_state.protected_ports.len(),
        grouped_rules,
        candidate_apps,
        blocked_ports: render_port_summary(cfg, &runtime_state.protected_ports),
        backend: runtime_state.backend.clone(),
        fw_caps: summarize_capabilities(&runtime_state.firewall_capabilities),
        self_test: summarize_self_tests(&runtime_state.firewall_self_tests),
        local_snapshots: summarize_local_snapshots(&runtime_state.local_address_snapshots),
        reapply_reason: if reapply_reason.is_empty() { "none".to_string() } else { reapply_reason.to_string() },
        protection_scope: if cfg.user_apps_only {
            "protect_any_ports_from_user_apps".to_string()
        } else {
            "protect_any_ports_from_user_and_system_apps".to_string()
        },
        trusted_packages: cfg.trusted_client_packages.len(),
        trusted_uids: cfg.trusted_client_uids.len(),
        reaction_mode: cfg.reaction_mode.clone(),
        loop_interval_ms: cfg.loop_interval_ms,
        reload_check_secs: cfg.reload_check_secs,
        network_refresh_secs: cfg.network_refresh_secs,
        package_refresh_secs: cfg.package_refresh_secs,
        counter_refresh_loops: cfg.counter_refresh_loops,
        updated_at_secs: state_updated_at_secs,
    }
}

fn dashboard_snapshot_standby(cfg: &Config, reason: &str, now: u64) -> DashboardSnapshot {
    DashboardSnapshot {
        status: "off".to_string(),
        listeners: 0,
        ports: 0,
        grouped_rules: 0,
        candidate_apps: 0,
        blocked_ports: "none".to_string(),
        backend: "standby".to_string(),
        fw_caps: "none".to_string(),
        self_test: "none".to_string(),
        local_snapshots: "none".to_string(),
        reapply_reason: reason.to_string(),
        protection_scope: if cfg.user_apps_only {
            "protect_any_ports_from_user_apps".to_string()
        } else {
            "protect_any_ports_from_user_and_system_apps".to_string()
        },
        trusted_packages: cfg.trusted_client_packages.len(),
        trusted_uids: cfg.trusted_client_uids.len(),
        reaction_mode: cfg.reaction_mode.clone(),
        loop_interval_ms: cfg.loop_interval_ms,
        reload_check_secs: cfg.reload_check_secs,
        network_refresh_secs: cfg.network_refresh_secs,
        package_refresh_secs: cfg.package_refresh_secs,
        counter_refresh_loops: cfg.counter_refresh_loops,
        updated_at_secs: now,
    }
}

fn tick_active(
    cfg: &Config,
    package_cache: &mut PackageCache,
    network_monitor: &mut NetworkMonitor,
    dashboard: &mut DashboardWriter,
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

        let reapply_reason = reapply_reasons.join(" | ");
        let snapshot = dashboard_snapshot_active(
            cfg,
            &runtime_state,
            decision.app_uids.len(),
            report.actual_rule_count,
            &reapply_reason,
            runtime_state.last_apply_epoch_secs,
        );
        dashboard.update(render_dashboard(&snapshot), now)?;

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
                reapply_reason,
                config::SETTINGS_DIR,
            ),
        );
    }

    if !changed {
        let snapshot = dashboard_snapshot_active(
            cfg,
            &runtime_state,
            decision.app_uids.len(),
            runtime_state.applied_rules.len(),
            "none",
            runtime_state.last_apply_epoch_secs,
        );
        dashboard.update(render_dashboard(&snapshot), now)?;
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
    let mut dashboard = DashboardWriter::default();

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
                if let Err(err) = enter_standby(&cfg, "config active_protection=off", &mut dashboard) {
                    logger::error("daemon", &format!("standby enter failed: {err:#}"));
                } else {
                    standby_applied = true;
                    network_monitor.invalidate();
                }
            }
        } else {
            standby_applied = false;
            if let Err(err) = tick_active(&cfg, &mut package_cache, &mut network_monitor, &mut dashboard, loop_index) {
                logger::error("daemon", &format!("tick failed: {err:#}"));
            }
        }

        let _ = dashboard.flush_if_due(now_secs());

        if once {
            break;
        }

        thread::sleep(Duration::from_millis(cfg.loop_interval_ms.max(500)));
    }
    Ok(())
}
