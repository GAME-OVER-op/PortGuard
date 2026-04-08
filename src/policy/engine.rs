use crate::{
    config::Config,
    types::{DesiredRule, Listener, PackageIndex},
};
use anyhow::{bail, Result};
use std::collections::BTreeSet;

#[derive(Debug, Clone)]
pub struct PolicyDecision {
    pub protected_listeners: Vec<Listener>,
    pub trusted_client_uids: Vec<u32>,
    pub app_uids: Vec<u32>,
    pub rules: Vec<DesiredRule>,
}

fn is_loopback_ip(ip: &str) -> bool {
    ip == "127.0.0.1" || ip.starts_with("127.")
}

fn any_owner_package_in(list: &[String], listener: &Listener) -> bool {
    listener
        .packages
        .iter()
        .any(|pkg| list.iter().any(|v| v == pkg))
}

pub fn build(cfg: &Config, listeners: &[Listener], packages: &PackageIndex) -> Result<PolicyDecision> {
    let trusted_from_packages = packages.resolve_uids_for_packages(&cfg.trusted_client_packages);
    let mut trusted_client_uids: BTreeSet<u32> = cfg.trusted_client_uids.iter().copied().collect();
    trusted_client_uids.extend(trusted_from_packages);

    let app_uids: BTreeSet<u32> = packages.app_uids();

    let protected_listeners: Vec<Listener> = listeners
        .iter()
        .filter(|listener| {
            (!cfg.protect_loopback_only || is_loopback_ip(&listener.local_ip))
                && !any_owner_package_in(&cfg.ignored_owner_packages, listener)
        })
        .cloned()
        .collect();

    let mut rules_set: BTreeSet<DesiredRule> = BTreeSet::new();

    for listener in &protected_listeners {
        for uid in &app_uids {
            if *uid == listener.uid {
                continue;
            }
            if trusted_client_uids.contains(uid) {
                continue;
            }
            rules_set.insert(DesiredRule {
                blocked_uid: *uid,
                dst_port: listener.port,
                owner_uid: listener.uid,
            });
        }
    }

    if rules_set.len() > cfg.max_rules {
        bail!(
            "desired rule count {} exceeds max_rules {}",
            rules_set.len(),
            cfg.max_rules
        );
    }

    Ok(PolicyDecision {
        protected_listeners,
        trusted_client_uids: trusted_client_uids.into_iter().collect(),
        app_uids: app_uids.into_iter().collect(),
        rules: rules_set.into_iter().collect(),
    })
}
