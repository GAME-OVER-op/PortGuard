use crate::{
    config::{self, Config},
    detector,
    discovery,
    firewall,
    logger,
    policy,
    procfs,
    runtime::state::{DetectorState, RuntimeState},
    types::{Listener, PackageIndex},
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

fn clear_runtime_and_detector_state(cfg: &Config) -> Result<()> {
    RuntimeState::default().save(&runtime_state_path(cfg))?;
    DetectorState::default().save(&detector_state_path(cfg))?;
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

fn tick_active(cfg: &Config, package_cache: &mut PackageCache, loop_index: u64) -> Result<()> {
    fs::create_dir_all(&cfg.state_dir)?;

    let package_index = package_cache.get(cfg)?.clone();
    let listeners = discovery::listeners::discover(cfg, &package_index)?;
    let decision = policy::engine::build(cfg, &listeners, &package_index)?;

    let runtime_path = runtime_state_path(cfg);
    let mut runtime_state = RuntimeState::load_or_default(&runtime_path)?;

    let desired_ports = protected_ports(&decision.protected_listeners);
    let changed = runtime_state.desired_rules != decision.rules
        || runtime_state.protected_listeners != decision.protected_listeners
        || runtime_state.trusted_client_uids != decision.trusted_client_uids;

    if changed {
        let report = firewall::iptables::apply_rules(&cfg.chain_name, &decision.rules)?;
        runtime_state.desired_rules = decision.rules.clone();
        runtime_state.protected_listeners = decision.protected_listeners.clone();
        runtime_state.protected_ports = desired_ports.clone();
        runtime_state.trusted_client_uids = decision.trusted_client_uids.clone();
        runtime_state.applied_rules = report.applied_rules.clone();
        runtime_state.backend = report.backend.clone();
        runtime_state.last_apply_epoch_secs = now_secs();
        runtime_state.save(&runtime_path)?;

        logger::info(
            "daemon",
            &format!(
                "active protection: on ports={} rules={} backend={} blocked_ports=[{}] trusted_packages={} trusted_uids={} reaction_mode={} settings_dir={}",
                runtime_state.protected_ports.len(),
                report.actual_rule_count,
                report.backend,
                render_port_summary(cfg, &runtime_state.protected_ports),
                cfg.trusted_client_packages.len(),
                cfg.trusted_client_uids.len(),
                cfg.reaction_mode,
                config::SETTINGS_DIR,
            ),
        );
    }

    let should_refresh_counters = loop_index % cfg.counter_refresh_loops.max(1) == 0;
    if should_refresh_counters && !runtime_state.applied_rules.is_empty() {
        match firewall::iptables::read_counters(&cfg.chain_name, &runtime_state.applied_rules) {
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
                                "settings reloaded: active_protection={} reaction_mode={} trusted_packages={} trusted_uids={} kill_exceptions={} scan_ignores={} reload_check_secs={} settings_dir={}",
                                new_cfg.active_protection,
                                new_cfg.reaction_mode,
                                new_cfg.trusted_client_packages.len(),
                                new_cfg.trusted_client_uids.len(),
                                new_cfg.kill_exceptions_packages.len(),
                                new_cfg.scan_ignore_packages.len(),
                                new_cfg.reload_check_secs,
                                config::SETTINGS_DIR,
                            ),
                        );
                        cfg = new_cfg;
                        package_cache.invalidate();
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
                }
            }
        } else {
            standby_applied = false;
            if let Err(err) = tick_active(&cfg, &mut package_cache, loop_index) {
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
