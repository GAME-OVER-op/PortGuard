use crate::{
    config::{self, Config},
    detector,
    discovery,
    firewall,
    logger,
    policy,
    procfs,
    runtime::state::{
        ensure_incidents_file, write_json_if_changed, write_json_value_if_changed, DetectorState, RuntimeState,
    },
    types::{
        FirewallProtoCapability, FirewallSelfTestResult, Listener, LocalAddressSnapshot, PackageIndex,
        Proto,
    },
};
use anyhow::Result;
use std::{
    cmp,
    collections::{BTreeMap, BTreeSet},
    fs,
    path::PathBuf,
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};


const TAB_LOG_PATH: &str = "/data/adb/modules/PortGuard/log/tab.log";
const TAB_LOG_MIN_WRITE_SECS: u64 = 5;
const SELF_TEST_REFRESH_SECS: u64 = 3600;

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
    firewall_reconcile_secs: u64,
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
        ("firewall_reconcile_secs", snapshot.firewall_reconcile_secs.to_string()),
    ];

    let label_width = rows.iter().map(|(k, _)| k.len()).max().unwrap_or(0);
    let content_width = rows
        .iter()
        .map(|(_, v)| label_width + 3 + v.len())
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

fn status_path(cfg: &Config) -> PathBuf {
    PathBuf::from(&cfg.state_dir).join("status.json")
}

fn summary_path(cfg: &Config) -> PathBuf {
    PathBuf::from(&cfg.state_dir).join("summary.json")
}

fn public_state_files_missing(cfg: &Config) -> bool {
    !status_path(cfg).exists() || !summary_path(cfg).exists()
}

fn publish_public_state(
    cfg: &Config,
    runtime: &RuntimeState,
    standby: bool,
    message: &str,
    updated_at_secs: u64,
) -> Result<()> {
    ensure_incidents_file(&cfg.state_dir)?;
    let status = serde_json::json!({
        "active_protection": cfg.active_protection.as_str(),
        "active": cfg.active_protection_enabled() && !standby,
        "standby": standby,
        "protected_ports": runtime.protected_ports.len(),
        "active_rules": runtime.applied_rules.len(),
        "backend": runtime.backend.as_str(),
        "blocked_ports": runtime.protected_ports.clone(),
        "message": message,
        "status_text": message,
        "updated_at": updated_at_secs.to_string(),
        "firewall_apply_complete": runtime.firewall_apply_complete,
        "consecutive_apply_failures": runtime.consecutive_apply_failures,
        "next_apply_retry_epoch_secs": runtime.next_apply_retry_epoch_secs,
        "last_apply_error": runtime.last_apply_error.as_str(),
    });
    let summary = serde_json::json!({
        "active_protection": cfg.active_protection.as_str(),
        "standby": standby,
        "protected_ports": runtime.protected_ports.len(),
        "ports": runtime.protected_ports.len(),
        "active_rules": runtime.applied_rules.len(),
        "rules": runtime.applied_rules.len(),
        "backend": runtime.backend.as_str(),
        "blocked_ports": runtime.protected_ports.clone(),
        "message": message,
        "updated_at": updated_at_secs.to_string(),
        "firewall_apply_complete": runtime.firewall_apply_complete,
        "firewall_capabilities": runtime.firewall_capabilities.clone(),
        "last_apply_error": runtime.last_apply_error.as_str(),
    });
    write_json_value_if_changed(&status_path(cfg), &status)?;
    write_json_value_if_changed(&summary_path(cfg), &summary)?;
    Ok(())
}

fn protected_ports(listeners: &[Listener]) -> Vec<u16> {
    let mut ports: Vec<u16> = listeners.iter().map(|v| v.port).collect();
    ports.sort();
    ports.dedup();
    ports
}

const PACKAGE_SOURCE_CHECK_SECS: u64 = 15;
const TRANSIENT_UID_REFRESH_SECS: u64 = 15;

fn package_source_stamp(cfg: &Config) -> Vec<(String, u64, u64)> {
    cfg.package_uid_sources
        .iter()
        .map(|path| {
            let metadata = fs::metadata(path).ok();
            let len = metadata.as_ref().map(|m| m.len()).unwrap_or(0);
            let modified = metadata
                .and_then(|m| m.modified().ok())
                .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
                .map(|duration| duration.as_secs())
                .unwrap_or(0);
            (path.clone(), len, modified)
        })
        .collect()
}

#[derive(Debug, Clone, Default)]
struct PackageCache {
    revision: u64,
    loaded_at_secs: u64,
    last_source_check_secs: u64,
    last_transient_refresh_secs: u64,
    source_stamp: Vec<(String, u64, u64)>,
    refresh_pending: bool,
    consecutive_failures: u32,
    next_retry_secs: u64,
    index: PackageIndex,
}

impl PackageCache {
    fn get<'a>(&'a mut self, cfg: &Config) -> Result<(&'a PackageIndex, u64)> {
        let now = now_secs();
        let source_check_due = self.last_source_check_secs == 0
            || now.saturating_sub(self.last_source_check_secs) >= PACKAGE_SOURCE_CHECK_SECS;
        let mut source_changed = false;
        if source_check_due {
            self.last_source_check_secs = now;
            let current_stamp = package_source_stamp(cfg);
            source_changed = !self.source_stamp.is_empty() && current_stamp != self.source_stamp;
            if source_changed {
                self.refresh_pending = true;
            }
            if self.source_stamp.is_empty() {
                self.source_stamp = current_stamp;
            }
        }

        let expired = self.index.package_to_uids.is_empty()
            || source_changed
            || self.refresh_pending
            || now.saturating_sub(self.loaded_at_secs) >= cfg.package_refresh_secs.max(120);
        if expired && now >= self.next_retry_secs {
            match procfs::packages::load_package_index(cfg) {
                Ok(index) => {
                    if self.index != index {
                        self.revision = self.revision.saturating_add(1);
                    }
                    self.index = index;
                    self.loaded_at_secs = now;
                    self.last_transient_refresh_secs = now;
                    self.source_stamp = package_source_stamp(cfg);
                    self.refresh_pending = false;
                    self.consecutive_failures = 0;
                    self.next_retry_secs = 0;
                }
                Err(err) => {
                    self.refresh_pending = true;
                    self.consecutive_failures = self.consecutive_failures.saturating_add(1);
                    let shift = self.consecutive_failures.saturating_sub(1).min(6);
                    let delay = 5_u64.saturating_mul(1_u64 << shift).min(300);
                    self.next_retry_secs = now.saturating_add(delay);
                    if self.index.package_to_uids.is_empty() {
                        return Err(err);
                    }
                    logger::warn(
                        "packages",
                        &format!(
                            "package refresh failed; using previous cache and retrying in {}s: {:#}",
                            delay, err
                        ),
                    );
                }
            }
        } else if expired && self.index.package_to_uids.is_empty() {
            anyhow::bail!(
                "package cache unavailable; retry scheduled in {}s",
                self.next_retry_secs.saturating_sub(now)
            );
        } else if self.last_transient_refresh_secs == 0
            || now.saturating_sub(self.last_transient_refresh_secs) >= TRANSIENT_UID_REFRESH_SECS
        {
            // Isolated/SDK-sandbox processes are short lived and are not reliably
            // represented by packages.list. Refresh only these UIDs cheaply from
            // /proc instead of invoking PackageManager every time.
            if self
                .index
                .set_transient_app_uids(procfs::packages::load_transient_app_uids())
            {
                self.revision = self.revision.saturating_add(1);
            }
            self.last_transient_refresh_secs = now;
        }
        Ok((&self.index, self.revision))
    }

    fn invalidate(&mut self) {
        self.loaded_at_secs = 0;
        self.last_source_check_secs = 0;
        self.last_transient_refresh_secs = 0;
        self.source_stamp.clear();
        self.refresh_pending = false;
        self.consecutive_failures = 0;
        self.next_retry_secs = 0;
        self.index = PackageIndex::default();
        self.revision = self.revision.saturating_add(1);
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
struct ListenerSemanticKey {
    proto: Proto,
    local_ip: String,
    port: u16,
    uid: u32,
    packages: Vec<String>,
}

#[derive(Debug, Clone, Default)]
struct PolicyCache {
    cfg: Option<Config>,
    package_revision: u64,
    listener_keys: BTreeSet<ListenerSemanticKey>,
    decision: Option<policy::engine::PolicyDecision>,
    last_error: Option<String>,
}

impl PolicyCache {
    fn get_or_build<'a>(
        &'a mut self,
        cfg: &Config,
        listeners: &[Listener],
        packages: &PackageIndex,
        package_revision: u64,
    ) -> Result<&'a policy::engine::PolicyDecision> {
        let keys = listener_semantic_keys(listeners);
        let same_inputs = self.cfg.as_ref() == Some(cfg)
            && self.package_revision == package_revision
            && self.listener_keys == keys;

        if same_inputs {
            if let Some(error) = self.last_error.as_ref() {
                return Err(anyhow::anyhow!(error.clone()));
            }
        } else {
            match policy::engine::build(cfg, listeners, packages) {
                Ok(decision) => {
                    self.cfg = Some(cfg.clone());
                    self.package_revision = package_revision;
                    self.listener_keys = keys;
                    self.last_error = None;
                    self.decision = Some(decision);
                }
                Err(err) => {
                    let error = format!("{err:#}");
                    self.cfg = Some(cfg.clone());
                    self.package_revision = package_revision;
                    self.listener_keys = keys;
                    self.decision = None;
                    self.last_error = Some(error.clone());
                    return Err(anyhow::anyhow!(error));
                }
            }
        }

        // All mutations are complete before a reference with lifetime 'a is
        // returned. Keep the diagnostic refresh borrow scoped to this block so
        // it cannot overlap the final `&'a` borrow of self.decision.
        if same_inputs {
            // PID/inode/process-name are diagnostics, not firewall-policy
            // inputs. Refresh that metadata without rebuilding the expensive
            // listener x UID rule set.
            let current_listeners = policy::engine::select_protected_listeners(cfg, listeners);
            if let Some(decision) = self.decision.as_mut() {
                if decision.protected_listeners != current_listeners {
                    decision.protected_listeners = current_listeners;
                }
            }
        }

        self.decision
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("policy cache unavailable after rebuild"))
    }

    fn invalidate(&mut self) {
        self.cfg = None;
        self.package_revision = 0;
        self.listener_keys.clear();
        self.decision = None;
        self.last_error = None;
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


#[derive(Debug, Clone)]
struct UdpCandidate {
    first_seen_secs: u64,
    last_seen_secs: u64,
    mature: bool,
    listener: Listener,
}

#[derive(Debug, Clone)]
struct ListenerStabilizer {
    udp_candidates: BTreeMap<String, UdpCandidate>,
    ephemeral_low: u16,
    ephemeral_high: u16,
}

impl Default for ListenerStabilizer {
    fn default() -> Self {
        let (ephemeral_low, ephemeral_high) = fs::read_to_string("/proc/sys/net/ipv4/ip_local_port_range")
            .ok()
            .and_then(|raw| {
                let mut parts = raw.split_whitespace();
                let low = parts.next()?.parse::<u16>().ok()?;
                let high = parts.next()?.parse::<u16>().ok()?;
                Some((low, high))
            })
            .unwrap_or((32768, 60999));
        Self {
            udp_candidates: BTreeMap::new(),
            ephemeral_low,
            ephemeral_high,
        }
    }
}

impl ListenerStabilizer {
    fn invalidate(&mut self) {
        self.udp_candidates.clear();
    }

    fn udp_key(listener: &Listener) -> String {
        format!(
            "{}|{}|{}|{}",
            listener.proto.short_label(),
            listener.local_ip,
            listener.port,
            listener.uid
        )
    }

    fn proto_enabled(cfg: &Config, proto: Proto) -> bool {
        match proto {
            Proto::Udp4 => cfg.udp4_enabled,
            Proto::Udp6 => cfg.udp6_enabled,
            _ => true,
        }
    }

    fn filter(&mut self, cfg: &Config, listeners: Vec<Listener>, now: u64) -> Vec<Listener> {
        self.udp_candidates
            .retain(|_, candidate| Self::proto_enabled(cfg, candidate.listener.proto));

        let mut out = Vec::new();
        let mut seen = BTreeSet::new();

        for listener in listeners {
            if !matches!(listener.proto, Proto::Udp4 | Proto::Udp6) {
                out.push(listener);
                continue;
            }

            let key = Self::udp_key(&listener);
            seen.insert(key.clone());
            let candidate = self.udp_candidates.entry(key).or_insert_with(|| UdpCandidate {
                first_seen_secs: now,
                last_seen_secs: now,
                mature: false,
                listener: listener.clone(),
            });
            candidate.last_seen_secs = now;
            candidate.listener = listener;

            let in_ephemeral_range = candidate.listener.port >= self.ephemeral_low
                && candidate.listener.port <= self.ephemeral_high;
            let required_age = if in_ephemeral_range {
                cfg.udp_ephemeral_stabilize_secs
            } else {
                cfg.udp_stabilize_secs
            };
            if now.saturating_sub(candidate.first_seen_secs) >= required_age {
                candidate.mature = true;
            }
            if candidate.mature {
                out.push(candidate.listener.clone());
            }
        }

        let mut remove = Vec::new();
        for (key, candidate) in &self.udp_candidates {
            if seen.contains(key) {
                continue;
            }
            let missing_for = now.saturating_sub(candidate.last_seen_secs);
            if candidate.mature && missing_for <= cfg.udp_grace_secs {
                out.push(candidate.listener.clone());
            } else if missing_for > cfg.udp_grace_secs {
                remove.push(key.clone());
            }
        }
        for key in remove {
            self.udp_candidates.remove(&key);
        }

        out.sort_by_key(|v| (v.proto, v.port, v.uid, v.pid.unwrap_or(-1), v.inode));
        out
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
    write_json_if_changed(&firewall_caps_path(cfg), &capabilities)
}

fn save_firewall_self_tests(cfg: &Config, results: &[FirewallSelfTestResult]) -> Result<()> {
    write_json_if_changed(&firewall_selftest_path(cfg), &results)
}

fn clear_runtime_and_detector_state(cfg: &Config) -> Result<()> {
    let runtime_path = runtime_state_path(cfg);
    let detector_path = detector_state_path(cfg);
    let runtime = RuntimeState::default();
    runtime.save(&runtime_path)?;
    DetectorState::default().save(&detector_path)?;
    save_firewall_capabilities(cfg, &runtime.firewall_capabilities)?;
    save_firewall_self_tests(cfg, &runtime.firewall_self_tests)?;
    ensure_incidents_file(&cfg.state_dir)?;
    Ok(())
}

fn enter_standby(cfg: &Config, reason: &str, dashboard: &mut DashboardWriter) -> Result<()> {
    fs::create_dir_all(&cfg.state_dir)?;
    firewall::iptables::clear_chain(&cfg.chain_name)?;
    clear_runtime_and_detector_state(cfg)?;
    let now = now_secs();
    let runtime = RuntimeState::default();
    publish_public_state(cfg, &runtime, true, reason, now)?;
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
        firewall_reconcile_secs: cfg.firewall_reconcile_secs,
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
        firewall_reconcile_secs: cfg.firewall_reconcile_secs,
        updated_at_secs: now,
    }
}

fn listener_semantic_keys(listeners: &[Listener]) -> BTreeSet<ListenerSemanticKey> {
    listeners
        .iter()
        .map(|listener| ListenerSemanticKey {
            proto: listener.proto,
            local_ip: listener.local_ip.clone(),
            port: listener.port,
            uid: listener.uid,
            packages: listener.packages.clone(),
        })
        .collect()
}

fn apply_retry_delay_secs(consecutive_failures: u32) -> u64 {
    let shift = consecutive_failures.saturating_sub(1).min(6);
    5_u64.saturating_mul(1_u64 << shift).min(300)
}

fn tick_active(
    cfg: &Config,
    package_cache: &mut PackageCache,
    policy_cache: &mut PolicyCache,
    network_monitor: &mut NetworkMonitor,
    listener_stabilizer: &mut ListenerStabilizer,
    runtime_state: &mut RuntimeState,
    dashboard: &mut DashboardWriter,
    last_counter_refresh_secs: &mut u64,
    last_integrity_check_secs: &mut u64,
) -> Result<()> {
    fs::create_dir_all(&cfg.state_dir)?;

    // Hot path: /proc discovery + pure policy calculation. No xtables command is
    // executed unless the effective firewall policy changed or a slow maintenance
    // timer explicitly requests reconciliation/counter sampling.
    // Borrow the cached package index in place. Cloning it here used to copy
    // thousands of package names/UID vectors on every 3 s discovery tick.
    let (package_index, package_revision) = package_cache.get(cfg)?;
    let discovered_listeners = discovery::listeners::discover(cfg, package_index)?;
    let listeners = listener_stabilizer.filter(cfg, discovered_listeners, now_secs());
    let decision = policy_cache.get_or_build(
        cfg,
        &listeners,
        package_index,
        package_revision,
    )?;

    let runtime_path = runtime_state_path(cfg);
    network_monitor.seed_from_runtime(runtime_state);

    let desired_ports = protected_ports(&decision.protected_listeners);
    let now = now_secs();
    let mut reapply_reasons = Vec::new();
    let mut runtime_dirty = false;

    let policy_changed = runtime_state.desired_rules != decision.rules;
    if policy_changed {
        reapply_reasons.push("desired_rules_changed".to_string());
    } else if !runtime_state.firewall_apply_complete
        && now >= runtime_state.next_apply_retry_epoch_secs
    {
        reapply_reasons.push(format!(
            "retry_incomplete_apply failures={} last_error={}",
            runtime_state.consecutive_apply_failures,
            runtime_state.last_apply_error
        ));
    }
    if !decision.rules.is_empty()
        && runtime_state.firewall_capabilities.is_empty()
        && (runtime_state.firewall_apply_complete || now >= runtime_state.next_apply_retry_epoch_secs)
    {
        reapply_reasons.push("firewall_capabilities_missing".to_string());
    }

    // Listener PID/inode/process-name churn does not alter firewall policy, but
    // it is still useful diagnostic state. PolicyCache refreshes this metadata
    // without rebuilding rules, so persist it without touching iptables.
    let metadata_changed = runtime_state.protected_listeners != decision.protected_listeners
        || runtime_state.protected_ports != desired_ports;

    let local_ip_list_protos = active_local_ip_list_protos(&runtime_state.firewall_capabilities);
    if !local_ip_list_protos.is_empty() && network_monitor.due(now, cfg.network_refresh_secs) {
        let current_snapshots = local_ip_list_snapshots_for_protos(&local_ip_list_protos);
        let network_changed = current_snapshots != network_monitor.snapshots;
        if network_changed {
            reapply_reasons.push(format!(
                "network_local_addresses_changed old={} new={}",
                summarize_local_snapshots(&network_monitor.snapshots),
                summarize_local_snapshots(&current_snapshots)
            ));
        }
        network_monitor.remember(now, current_snapshots);
        // Keep the frequent liveness timestamp in memory. Persist it only when
        // something else is already dirty; writing runtime_state.json every 30 s
        // just because the address set stayed identical is unnecessary flash IO.
        runtime_state.last_network_check_epoch_secs = now;
        runtime_dirty |= network_changed;
    }

    let integrity_due = *last_integrity_check_secs == 0
        || now.saturating_sub(*last_integrity_check_secs) >= cfg.firewall_reconcile_secs;
    if integrity_due {
        *last_integrity_check_secs = now;
        runtime_state.last_reconcile_epoch_secs = now;
        runtime_dirty = true;

        if let Err(err) = firewall::iptables::verify_applied_rules(
            &cfg.chain_name,
            &runtime_state.applied_rules,
        ) {
            reapply_reasons.push(format!("firewall_integrity_lost: {err:#}"));
        }
        if runtime_state.applied_rules.is_empty() && !runtime_state.desired_rules.is_empty() {
            reapply_reasons.push("firewall_integrity_lost: desired rules exist but applied rules are empty".to_string());
        }
    }

    let changed = !reapply_reasons.is_empty();

    if changed {
        // Prefer the backend that was already proven to work. A full capability
        // probe is only used on first setup or as fallback after cached apply fails.
        let report_result = if !runtime_state.firewall_capabilities.is_empty() {
            let force_reapply_all = !runtime_state.firewall_apply_complete
                || reapply_reasons
                    .iter()
                    .any(|reason| !reason.starts_with("desired_rules_changed"));
            match firewall::iptables::apply_rules_cached(
                &cfg.chain_name,
                &decision.rules,
                &runtime_state.desired_rules,
                &runtime_state.applied_rules,
                &runtime_state.firewall_capabilities,
                force_reapply_all,
            ) {
                Ok(report) => Ok(report),
                Err(err) => {
                    logger::warn(
                        "daemon",
                        &format!("cached firewall backend failed; reprobe once: {err:#}"),
                    );
                    firewall::iptables::apply_rules(&cfg.chain_name, &decision.rules)
                }
            }
        } else {
            firewall::iptables::apply_rules(&cfg.chain_name, &decision.rules)
        };

        let mut report = match report_result {
            Ok(report) => report,
            Err(err) => {
                // A hard xtables failure must not fall through to the 2–3 second
                // discovery loop. Remember the desired policy so the normal
                // incomplete-apply backoff gates the next attempt. Keep known
                // applied rules/capabilities untouched because the old A/B slot may
                // still be protecting the device.
                runtime_state.desired_rules = decision.rules.clone();
                runtime_state.protected_listeners = decision.protected_listeners.clone();
                runtime_state.protected_ports = desired_ports.clone();
                runtime_state.trusted_client_uids = decision.trusted_client_uids.clone();
                runtime_state.firewall_apply_complete = false;
                runtime_state.consecutive_apply_failures = runtime_state
                    .consecutive_apply_failures
                    .saturating_add(1);
                runtime_state.last_apply_failed_epoch_secs = now;
                let retry_delay = apply_retry_delay_secs(runtime_state.consecutive_apply_failures);
                runtime_state.next_apply_retry_epoch_secs = now.saturating_add(retry_delay);
                runtime_state.last_apply_error = format!("{err:#}");
                runtime_state.save(&runtime_path)?;
                publish_public_state(
                    cfg,
                    runtime_state,
                    false,
                    "degraded: firewall command failed; retry scheduled",
                    now,
                )?;
                *last_counter_refresh_secs = now;
                let snapshot = dashboard_snapshot_active(
                    cfg,
                    runtime_state,
                    decision.app_uids.len(),
                    runtime_state.applied_rules.len(),
                    &format!("apply_failed_retry_in={}s", retry_delay),
                    now,
                );
                dashboard.update(render_dashboard(&snapshot), now)?;
                logger::error(
                    "daemon",
                    &format!(
                        "firewall command failed; retry_in={}s failures={} error={:#}",
                        retry_delay, runtime_state.consecutive_apply_failures, err
                    ),
                );
                return Ok(());
            }
        };

        let apply_complete = report.is_complete();
        if !apply_complete {
            // A/B updates leave the previously active chain in place when a
            // protocol cannot be switched. Keep those known-old rules in runtime
            // state until the retry succeeds; otherwise diagnostics/counters would
            // pretend that protection vanished while the kernel still enforces it.
            let failed_protos = report
                .capabilities
                .iter()
                .filter(|cap| {
                    (cap.rules_requested > 0 && !cap.supported)
                        || cap.last_error.starts_with("idle cleanup failed")
                })
                .map(|cap| cap.proto)
                .collect::<BTreeSet<_>>();
            for old_rule in runtime_state
                .applied_rules
                .iter()
                .filter(|rule| failed_protos.contains(&rule.proto))
            {
                if !report.applied_rules.contains(old_rule) {
                    report.applied_rules.push(old_rule.clone());
                }
            }
            report.actual_rule_count = report.applied_rules.len();
        }
        let integrity_repair = reapply_reasons
            .iter()
            .any(|reason| reason.starts_with("firewall_integrity_lost"));
        let self_test_due = apply_complete
            && cfg.active_self_test_enabled
            && (runtime_state.last_self_test_epoch_secs == 0
                || now.saturating_sub(runtime_state.last_self_test_epoch_secs) >= SELF_TEST_REFRESH_SECS
                || integrity_repair);
        let self_tests = if self_test_due {
            firewall::iptables::run_active_self_tests(
                &mut report.capabilities,
                cfg.self_test_timeout_ms,
            )
        } else if apply_complete && cfg.active_self_test_enabled {
            runtime_state.firewall_self_tests.clone()
        } else {
            Vec::new()
        };
        if self_test_due && self_tests.iter().any(|item| item.attempted && !item.ok) {
            *last_integrity_check_secs = 0;
        }

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
        if self_test_due {
            runtime_state.last_self_test_epoch_secs = now;
        } else if !cfg.active_self_test_enabled {
            runtime_state.last_self_test_epoch_secs = 0;
        }
        runtime_state.last_network_check_epoch_secs = now;
        runtime_state.firewall_apply_complete = apply_complete;
        if apply_complete {
            runtime_state.consecutive_apply_failures = 0;
            runtime_state.last_apply_failed_epoch_secs = 0;
            runtime_state.next_apply_retry_epoch_secs = 0;
            runtime_state.last_apply_error.clear();
        } else {
            runtime_state.consecutive_apply_failures = runtime_state
                .consecutive_apply_failures
                .saturating_add(1);
            runtime_state.last_apply_failed_epoch_secs = now;
            let retry_delay = apply_retry_delay_secs(runtime_state.consecutive_apply_failures);
            runtime_state.next_apply_retry_epoch_secs = now.saturating_add(retry_delay);
            runtime_state.last_apply_error = report.failures.join(" | ");
            runtime_state.last_self_test_epoch_secs = 0;
            logger::error(
                "daemon",
                &format!(
                    "firewall apply incomplete; retry_in={}s failures={} error={}",
                    retry_delay,
                    runtime_state.consecutive_apply_failures,
                    runtime_state.last_apply_error
                ),
            );
        }
        runtime_state.save(&runtime_path)?;
        save_firewall_capabilities(cfg, &runtime_state.firewall_capabilities)?;
        save_firewall_self_tests(cfg, &runtime_state.firewall_self_tests)?;
        let public_message = if runtime_state.firewall_apply_complete {
            "ok"
        } else {
            "degraded: firewall apply incomplete"
        };
        publish_public_state(cfg, runtime_state, false, public_message, now)?;

        // Do not immediately run another set of iptables counter commands after a
        // potentially expensive apply/self-test pass.
        *last_counter_refresh_secs = now;

        let reapply_reason = reapply_reasons.join(" | ");
        let snapshot = dashboard_snapshot_active(
            cfg,
            runtime_state,
            decision.app_uids.len(),
            report.actual_rule_count,
            &reapply_reason,
            now,
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
    } else {
        if metadata_changed {
            runtime_state.protected_listeners = decision.protected_listeners.clone();
            runtime_state.protected_ports = desired_ports.clone();
            runtime_dirty = true;
        }
        if runtime_state.trusted_client_uids != decision.trusted_client_uids {
            runtime_state.trusted_client_uids = decision.trusted_client_uids.clone();
            runtime_dirty = true;
        }

        // Active self-test is diagnostic/liveness work and must not require a
        // policy reapply. Run it at most hourly against the already-active
        // chains, so stable devices still get periodic verification without
        // rebuilding firewall rules just to perform the test.
        let periodic_self_test_due = cfg.active_self_test_enabled
            && runtime_state.firewall_apply_complete
            && !runtime_state.applied_rules.is_empty()
            && (runtime_state.last_self_test_epoch_secs == 0
                || now.saturating_sub(runtime_state.last_self_test_epoch_secs) >= SELF_TEST_REFRESH_SECS);
        let mut self_test_refreshed = false;
        if periodic_self_test_due {
            let mut capabilities = runtime_state.firewall_capabilities.clone();
            let self_tests = firewall::iptables::run_active_self_tests(
                &mut capabilities,
                cfg.self_test_timeout_ms,
            );
            if self_tests.iter().any(|item| item.attempted && !item.ok) {
                // Re-check topology on the next cheap loop. In particular this
                // quickly repairs a rare leftover test rule after cleanup failure.
                *last_integrity_check_secs = 0;
            }
            runtime_state.firewall_capabilities = capabilities;
            runtime_state.firewall_self_tests = self_tests;
            runtime_state.last_self_test_epoch_secs = now;
            save_firewall_capabilities(cfg, &runtime_state.firewall_capabilities)?;
            save_firewall_self_tests(cfg, &runtime_state.firewall_self_tests)?;
            runtime_dirty = true;
            self_test_refreshed = true;
        } else if !cfg.active_self_test_enabled && !runtime_state.firewall_self_tests.is_empty() {
            runtime_state.firewall_self_tests.clear();
            runtime_state.last_self_test_epoch_secs = 0;
            save_firewall_self_tests(cfg, &runtime_state.firewall_self_tests)?;
            runtime_dirty = true;
            self_test_refreshed = true;
        }

        if runtime_dirty {
            runtime_state.save(&runtime_path)?;
        }
        if metadata_changed || self_test_refreshed || public_state_files_missing(cfg) {
            let public_message = if runtime_state.firewall_apply_complete {
                "ok"
            } else {
                "degraded: waiting for firewall retry"
            };
            publish_public_state(cfg, runtime_state, false, public_message, now)?;
        }

        let dashboard_updated_at = if metadata_changed || self_test_refreshed {
            now
        } else {
            runtime_state
                .last_apply_epoch_secs
                .max(runtime_state.last_reconcile_epoch_secs)
        };
        let snapshot = dashboard_snapshot_active(
            cfg,
            runtime_state,
            decision.app_uids.len(),
            runtime_state.applied_rules.len(),
            "none",
            dashboard_updated_at,
        );
        dashboard.update(render_dashboard(&snapshot), now)?;
    }

    let counter_interval_secs = cfg.counter_refresh_interval_secs();
    let counter_due = *last_counter_refresh_secs == 0
        || now.saturating_sub(*last_counter_refresh_secs) >= counter_interval_secs;
    if counter_due && !runtime_state.applied_rules.is_empty() {
        // Advance the timer even on failure so a transient xtables problem cannot
        // create a tight retry loop and stall the phone.
        *last_counter_refresh_secs = now;
        match firewall::iptables::read_counters(&runtime_state.applied_rules) {
            Ok(counters) => {
                let detector_path = detector_state_path(cfg);
                let mut detector_state = DetectorState::load_or_default(&detector_path)?;
                let previous_detector_state = detector_state.clone();
                detector::scan::process_counters(cfg, &mut detector_state, &counters, package_index);
                if detector_state != previous_detector_state {
                    detector_state.save(&detector_path)?;
                }
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
    let mut policy_cache = PolicyCache::default();
    let mut network_monitor = NetworkMonitor::default();
    let mut listener_stabilizer = ListenerStabilizer::default();
    // Runtime state is hot data. Load it once at daemon start instead of reading
    // and parsing a potentially large JSON document on every discovery tick.
    let mut runtime_state = RuntimeState::load_or_default(&runtime_state_path(&cfg))?;
    let mut last_counter_refresh_secs = 0_u64;
    let mut last_integrity_check_secs = 0_u64;
    let mut last_settings_check = 0_u64;
    let mut standby_applied = false;
    let mut standby_failures = 0_u32;
    let mut next_standby_retry_secs = 0_u64;
    let mut dashboard = DashboardWriter::default();
    let mut last_tick_error = String::new();
    let mut last_tick_error_log_secs = 0_u64;

    loop {
        let now = now_secs();
        if last_settings_check == 0 || now.saturating_sub(last_settings_check) >= cfg.reload_check_secs.max(30) {
            last_settings_check = now;
            match config::load_module_settings() {
                Ok(new_cfg) => {
                    if new_cfg != cfg {
                        let previous_chain = cfg.chain_name.clone();
                        let chain_changed = previous_chain != new_cfg.chain_name;
                        let chain_cleanup_ok = if chain_changed {
                            // Never accept a renamed chain while the old OUTPUT
                            // hooks could still be live. Otherwise a transient
                            // xtables failure can leave both policies enforcing in
                            // parallel. Keep the current config and retry the reload
                            // later if cleanup fails.
                            match firewall::iptables::clear_chain(&previous_chain) {
                                Ok(()) => true,
                                Err(err) => {
                                    logger::warn(
                                        "daemon",
                                        &format!(
                                            "settings reload deferred: old firewall chain cleanup failed before rename {} -> {}: {err:#}",
                                            previous_chain, new_cfg.chain_name
                                        ),
                                    );
                                    false
                                }
                            }
                        } else {
                            true
                        };

                        if chain_cleanup_ok {
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
                            if chain_changed {
                                // A chain-name change does not alter DesiredRule, so
                                // explicitly invalidate the applied firewall metadata.
                                runtime_state = RuntimeState::default();
                            }
                            package_cache.invalidate();
                            policy_cache.invalidate();
                            network_monitor.invalidate();
                            listener_stabilizer.invalidate();
                            last_counter_refresh_secs = 0;
                            last_integrity_check_secs = 0;
                            standby_applied = false;
                            standby_failures = 0;
                            next_standby_retry_secs = 0;
                        }
                    }
                }
                Err(err) => {
                    logger::warn("daemon", &format!("settings reload skipped: {err:#}"));
                }
            }
        }

        if !cfg.active_protection_enabled() {
            if !standby_applied && now >= next_standby_retry_secs {
                if let Err(err) = enter_standby(&cfg, "config active_protection=off", &mut dashboard) {
                    standby_failures = standby_failures.saturating_add(1);
                    let retry_delay = apply_retry_delay_secs(standby_failures);
                    next_standby_retry_secs = now.saturating_add(retry_delay);
                    logger::error(
                        "daemon",
                        &format!("standby enter failed; retry_in={}s: {err:#}", retry_delay),
                    );
                } else {
                    standby_applied = true;
                    standby_failures = 0;
                    next_standby_retry_secs = 0;
                    runtime_state = RuntimeState::default();
                    network_monitor.invalidate();
                }
            }
        } else {
            standby_applied = false;
            standby_failures = 0;
            next_standby_retry_secs = 0;
            match tick_active(
                &cfg,
                &mut package_cache,
                &mut policy_cache,
                &mut network_monitor,
                &mut listener_stabilizer,
                &mut runtime_state,
                &mut dashboard,
                &mut last_counter_refresh_secs,
                &mut last_integrity_check_secs,
            ) {
                Ok(()) => {
                    last_tick_error.clear();
                    last_tick_error_log_secs = 0;
                }
                Err(err) => {
                    let text = format!("{err:#}");
                    let log_now = now_secs();
                    if text != last_tick_error
                        || last_tick_error_log_secs == 0
                        || log_now.saturating_sub(last_tick_error_log_secs) >= 60
                    {
                        logger::error("daemon", &format!("tick failed: {}", text));
                        last_tick_error = text;
                        last_tick_error_log_secs = log_now;
                    }
                }
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
