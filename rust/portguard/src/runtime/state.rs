use crate::types::{
    AppliedRule, DesiredRule, FirewallProtoCapability, FirewallSelfTestResult, Listener, LocalAddressSnapshot, Proto,
};
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, fs, fs::OpenOptions, io::Write, path::Path};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RuntimeState {
    pub desired_rules: Vec<DesiredRule>,
    pub protected_listeners: Vec<Listener>,
    pub protected_ports: Vec<u16>,
    pub trusted_client_uids: Vec<u32>,
    pub applied_rules: Vec<AppliedRule>,
    pub backend: String,
    pub firewall_capabilities: Vec<FirewallProtoCapability>,
    #[serde(default)]
    pub firewall_self_tests: Vec<FirewallSelfTestResult>,
    #[serde(default)]
    pub local_address_snapshots: Vec<LocalAddressSnapshot>,
    pub last_apply_epoch_secs: u64,
    #[serde(default)]
    pub last_self_test_epoch_secs: u64,
    #[serde(default)]
    pub last_network_check_epoch_secs: u64,
    #[serde(default)]
    pub last_reconcile_epoch_secs: u64,
    #[serde(default = "default_true")]
    pub firewall_apply_complete: bool,
    #[serde(default)]
    pub consecutive_apply_failures: u32,
    #[serde(default)]
    pub last_apply_failed_epoch_secs: u64,
    #[serde(default)]
    pub next_apply_retry_epoch_secs: u64,
    #[serde(default)]
    pub last_apply_error: String,
}

fn default_true() -> bool {
    true
}

impl Default for RuntimeState {
    fn default() -> Self {
        Self {
            desired_rules: Vec::new(),
            protected_listeners: Vec::new(),
            protected_ports: Vec::new(),
            trusted_client_uids: Vec::new(),
            applied_rules: Vec::new(),
            backend: String::new(),
            firewall_capabilities: Vec::new(),
            firewall_self_tests: Vec::new(),
            local_address_snapshots: Vec::new(),
            last_apply_epoch_secs: 0,
            last_self_test_epoch_secs: 0,
            last_network_check_epoch_secs: 0,
            last_reconcile_epoch_secs: 0,
            firewall_apply_complete: true,
            consecutive_apply_failures: 0,
            last_apply_failed_epoch_secs: 0,
            next_apply_retry_epoch_secs: 0,
            last_apply_error: String::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IncidentRecord {
    pub timestamp: u64,
    pub level: String,
    pub uid: u32,
    pub packages: Vec<String>,
    pub ports: Vec<u16>,
    pub action: String,
    pub message: String,
}

const INCIDENT_MAX_BYTES: u64 = 1_048_576;
const INCIDENT_KEEP_LINES: usize = 1_000;

fn compact_incidents_if_needed(path: &Path) -> Result<()> {
    let size = fs::metadata(path).map(|meta| meta.len()).unwrap_or(0);
    if size < INCIDENT_MAX_BYTES {
        return Ok(());
    }

    // This path runs only when the journal reaches 1 MiB. Keep a bounded tail
    // so months of operation cannot turn every UI refresh/backup into a large IO.
    let raw = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let mut lines = raw.lines().rev().take(INCIDENT_KEEP_LINES).collect::<Vec<_>>();
    lines.reverse();
    let mut compacted = lines.join("\n");
    if !compacted.is_empty() {
        compacted.push('\n');
    }
    let tmp = path.with_extension("jsonl.tmp");
    fs::write(&tmp, compacted).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, path)
        .with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
    Ok(())
}

pub fn append_incident(state_dir: &str, incident: &IncidentRecord) -> Result<()> {
    let path = Path::new(state_dir).join("incidents.jsonl");
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    compact_incidents_if_needed(&path)?;
    let mut file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
        .with_context(|| format!("open {}", path.display()))?;
    let line = serde_json::to_string(incident)?;
    writeln!(file, "{}", line).with_context(|| format!("append {}", path.display()))?;
    Ok(())
}

pub fn ensure_incidents_file(state_dir: &str) -> Result<()> {
    let path = Path::new(state_dir).join("incidents.jsonl");
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    if !path.exists() {
        fs::write(&path, "").with_context(|| format!("create {}", path.display()))?;
    }
    Ok(())
}

pub fn write_json_if_changed<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    let raw = serde_json::to_string_pretty(value)?;
    if fs::read_to_string(path).ok().as_deref() == Some(raw.as_str()) {
        return Ok(());
    }
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, &raw).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, path)
        .with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
    Ok(())
}

pub fn write_json_value_if_changed(path: &Path, value: &serde_json::Value) -> Result<()> {
    write_json_if_changed(path, value)
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
pub struct CounterSnapshot {
    pub packets: u64,
    pub bytes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct AttemptEvent {
    pub ts: u64,
    pub proto: Proto,
    pub dst_ports: Vec<u16>,
    pub packets: u64,
    pub bytes: u64,
    pub rule_hits: u64,
}

impl Default for AttemptEvent {
    fn default() -> Self {
        Self {
            ts: 0,
            proto: Proto::Tcp4,
            dst_ports: Vec::new(),
            packets: 0,
            bytes: 0,
            rule_hits: 0,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
pub struct DetectorState {
    pub last_seen_counters: BTreeMap<String, CounterSnapshot>,
    pub by_uid: BTreeMap<u32, Vec<AttemptEvent>>,
    pub last_warn_epoch: BTreeMap<String, u64>,
    pub last_action_epoch: BTreeMap<String, u64>,
}

fn read_json_or_default<T>(path: &Path) -> Result<T>
where
    T: for<'de> Deserialize<'de> + Default,
{
    if !path.exists() {
        return Ok(T::default());
    }
    let raw = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    match serde_json::from_str::<T>(&raw) {
        Ok(v) => Ok(v),
        Err(_) => Ok(T::default()),
    }
}

fn write_json<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    let tmp = path.with_extension("tmp");
    let raw = serde_json::to_string_pretty(value)?;
    fs::write(&tmp, raw).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, path)
        .with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
    Ok(())
}

impl RuntimeState {
    pub fn load_or_default(path: &Path) -> Result<Self> {
        read_json_or_default(path)
    }

    pub fn save(&self, path: &Path) -> Result<()> {
        write_json(path, self)
    }
}

impl DetectorState {
    pub fn load_or_default(path: &Path) -> Result<Self> {
        read_json_or_default(path)
    }

    pub fn save(&self, path: &Path) -> Result<()> {
        write_json(path, self)
    }
}
