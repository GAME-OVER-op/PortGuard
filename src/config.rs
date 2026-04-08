use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::{fs, path::Path};

pub const MODULE_ROOT: &str = "/data/adb/modules/PortGuard";
pub const SETTINGS_DIR: &str = "/data/adb/modules/PortGuard/settings";
pub const CONFIG_JSON_PATH: &str = "/data/adb/modules/PortGuard/settings/config.json";
pub const TRUSTED_PACKAGES_PATH: &str = "/data/adb/modules/PortGuard/settings/trusted_packages.txt";
pub const TRUSTED_UIDS_PATH: &str = "/data/adb/modules/PortGuard/settings/trusted_uids.txt";
pub const KILL_EXCEPTIONS_PATH: &str = "/data/adb/modules/PortGuard/settings/kill_exceptions.txt";
pub const SCAN_IGNORE_PACKAGES_PATH: &str = "/data/adb/modules/PortGuard/settings/scan_ignore_packages.txt";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub struct Config {
    pub chain_name: String,
    pub state_dir: String,
    pub loop_interval_ms: u64,
    pub reload_check_secs: u64,
    pub active_protection: String,
    pub protect_loopback_only: bool,
    pub tcp4_enabled: bool,
    pub resolve_process_details: bool,
    pub trusted_client_packages: Vec<String>,
    pub trusted_client_uids: Vec<u32>,
    pub kill_exceptions_packages: Vec<String>,
    pub scan_ignore_packages: Vec<String>,
    pub ignored_owner_packages: Vec<String>,
    pub suspicious_unique_ports: usize,
    pub suspicious_attempts: u64,
    pub suspicious_rule_hits: u64,
    pub scan_window_secs: u64,
    pub warn_cooldown_secs: u64,
    pub reaction_cooldown_secs: u64,
    pub max_rules: usize,
    pub auto_discover_packages: bool,
    pub package_uid_sources: Vec<String>,
    pub package_refresh_secs: u64,
    pub summary_port_limit: usize,
    pub counter_refresh_loops: u64,
    pub learning_mode: bool,
    pub reaction_mode: String,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            chain_name: "PORTGUARD".to_string(),
            state_dir: format!("{SETTINGS_DIR}/state"),
            loop_interval_ms: 3000,
            reload_check_secs: 30,
            active_protection: "on".to_string(),
            protect_loopback_only: false,
            tcp4_enabled: true,
            resolve_process_details: false,
            trusted_client_packages: vec!["com.example.portguardui".to_string()],
            trusted_client_uids: Vec::new(),
            kill_exceptions_packages: Vec::new(),
            scan_ignore_packages: Vec::new(),
            ignored_owner_packages: Vec::new(),
            suspicious_unique_ports: 2,
            suspicious_attempts: 2,
            suspicious_rule_hits: 1,
            scan_window_secs: 10,
            warn_cooldown_secs: 15,
            reaction_cooldown_secs: 60,
            max_rules: 100_000,
            auto_discover_packages: true,
            package_uid_sources: vec!["/data/system/packages.list".to_string()],
            package_refresh_secs: 60,
            summary_port_limit: 24,
            counter_refresh_loops: 2,
            learning_mode: false,
            reaction_mode: "off".to_string(),
        }
    }
}

impl Config {
    fn normalize(&mut self) {
        self.reload_check_secs = self.reload_check_secs.max(30);
        self.loop_interval_ms = self.loop_interval_ms.max(500);
        self.package_refresh_secs = self.package_refresh_secs.max(10);
        self.summary_port_limit = self.summary_port_limit.max(1);
        self.counter_refresh_loops = self.counter_refresh_loops.max(1);
        self.scan_window_secs = self.scan_window_secs.max(1);
        self.warn_cooldown_secs = self.warn_cooldown_secs.max(1);
        self.reaction_cooldown_secs = self.reaction_cooldown_secs.max(5);
        self.reaction_mode = self.reaction_mode.trim().to_ascii_lowercase();
        self.active_protection = self.active_protection.trim().to_ascii_lowercase();
        if self.active_protection != "off" {
            self.active_protection = "on".to_string();
        }
        dedup_sort_strings(&mut self.trusted_client_packages);
        dedup_sort_u32(&mut self.trusted_client_uids);
        dedup_sort_strings(&mut self.kill_exceptions_packages);
        dedup_sort_strings(&mut self.scan_ignore_packages);
        dedup_sort_strings(&mut self.ignored_owner_packages);
    }

    pub fn reaction_force_stop_enabled(&self) -> bool {
        self.reaction_mode == "force_stop"
    }

    pub fn active_protection_enabled(&self) -> bool {
        self.active_protection == "on"
    }

    pub fn uid_is_trusted(&self, uid: u32) -> bool {
        self.trusted_client_uids.iter().any(|v| *v == uid)
    }

    pub fn package_is_trusted(&self, pkg: &str) -> bool {
        self.trusted_client_packages.iter().any(|v| v == pkg)
    }

    pub fn package_is_kill_exempt(&self, pkg: &str) -> bool {
        self.kill_exceptions_packages.iter().any(|v| v == pkg)
            || self.package_is_trusted(pkg)
    }

    pub fn package_is_scan_ignored(&self, pkg: &str) -> bool {
        self.scan_ignore_packages.iter().any(|v| v == pkg)
    }
}

fn dedup_sort_strings(values: &mut Vec<String>) {
    values.retain(|v| !v.trim().is_empty());
    values.sort();
    values.dedup();
}

fn dedup_sort_u32(values: &mut Vec<u32>) {
    values.sort();
    values.dedup();
}

fn ensure_parent_dir(path: &str) -> Result<()> {
    if let Some(parent) = Path::new(path).parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    Ok(())
}

fn ensure_text_file(path: &str) -> Result<()> {
    if !Path::new(path).exists() {
        ensure_parent_dir(path)?;
        fs::write(path, "").with_context(|| format!("write {}", path))?;
    }
    Ok(())
}

fn parse_string_list(raw: &str) -> Vec<String> {
    let mut out = Vec::new();
    for line in raw.lines() {
        let base = line.split_once('#').map(|(left, _)| left).unwrap_or(line);
        let item = base.trim();
        if !item.is_empty() {
            out.push(item.to_string());
        }
    }
    dedup_sort_strings(&mut out);
    out
}

fn parse_u32_list(raw: &str) -> Vec<u32> {
    let mut out = Vec::new();
    for line in raw.lines() {
        let base = line.split_once('#').map(|(left, _)| left).unwrap_or(line);
        let item = base.trim();
        if item.is_empty() {
            continue;
        }
        if let Ok(value) = item.parse::<u32>() {
            out.push(value);
        }
    }
    dedup_sort_u32(&mut out);
    out
}

fn merge_unique_strings(into: &mut Vec<String>, extra: Vec<String>) {
    into.extend(extra);
    dedup_sort_strings(into);
}

fn merge_unique_u32(into: &mut Vec<u32>, extra: Vec<u32>) {
    into.extend(extra);
    dedup_sort_u32(into);
}

fn read_text_if_exists(path: &str) -> Result<String> {
    if !Path::new(path).exists() {
        return Ok(String::new());
    }
    fs::read_to_string(path).with_context(|| format!("read {}", path))
}

pub fn load_module_settings() -> Result<Config> {
    fs::create_dir_all(SETTINGS_DIR).with_context(|| format!("mkdir {}", SETTINGS_DIR))?;
    fs::create_dir_all(format!("{SETTINGS_DIR}/state"))
        .with_context(|| format!("mkdir {}/state", SETTINGS_DIR))?;

    if !Path::new(CONFIG_JSON_PATH).exists() {
        ensure_parent_dir(CONFIG_JSON_PATH)?;
        let default_cfg = Config::default();
        let raw = serde_json::to_string_pretty(&default_cfg)?;
        fs::write(CONFIG_JSON_PATH, raw).with_context(|| format!("write {}", CONFIG_JSON_PATH))?;
    }

    ensure_text_file(TRUSTED_PACKAGES_PATH)?;
    ensure_text_file(TRUSTED_UIDS_PATH)?;
    ensure_text_file(KILL_EXCEPTIONS_PATH)?;
    ensure_text_file(SCAN_IGNORE_PACKAGES_PATH)?;

    let raw = fs::read_to_string(CONFIG_JSON_PATH)
        .with_context(|| format!("read {}", CONFIG_JSON_PATH))?;
    let mut cfg: Config = serde_json::from_str(&raw)
        .with_context(|| format!("parse {}", CONFIG_JSON_PATH))?;

    merge_unique_strings(
        &mut cfg.trusted_client_packages,
        parse_string_list(&read_text_if_exists(TRUSTED_PACKAGES_PATH)?),
    );
    merge_unique_u32(
        &mut cfg.trusted_client_uids,
        parse_u32_list(&read_text_if_exists(TRUSTED_UIDS_PATH)?),
    );
    merge_unique_strings(
        &mut cfg.kill_exceptions_packages,
        parse_string_list(&read_text_if_exists(KILL_EXCEPTIONS_PATH)?),
    );
    merge_unique_strings(
        &mut cfg.scan_ignore_packages,
        parse_string_list(&read_text_if_exists(SCAN_IGNORE_PACKAGES_PATH)?),
    );

    cfg.normalize();
    Ok(cfg)
}
