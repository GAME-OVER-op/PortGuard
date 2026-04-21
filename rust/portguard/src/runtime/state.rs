use crate::types::{
    AppliedRule, DesiredRule, FirewallProtoCapability, FirewallSelfTestResult, Listener, LocalAddressSnapshot, Proto,
};
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, fs, path::Path};

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
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
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct CounterSnapshot {
    pub packets: u64,
    pub bytes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
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

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
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
