use crate::{
    config::Config,
    logger,
    reaction,
    runtime::state::{AttemptEvent, CounterSnapshot, DetectorState},
    types::{PackageIndex, Proto, RuleCounter},
};
use std::collections::{BTreeMap, BTreeSet};

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn prune_old_events(state: &mut DetectorState, window_secs: u64, now: u64) {
    for events in state.by_uid.values_mut() {
        events.retain(|ev| now.saturating_sub(ev.ts) <= window_secs);
    }
}

fn uid_label(packages: &PackageIndex, uid: u32) -> String {
    let names = packages.packages_for_uid(uid);
    if names.is_empty() {
        format!("uid={uid}")
    } else {
        format!("uid={uid} packages={}", names.join(","))
    }
}

fn render_ports(ports: &[u16]) -> String {
    ports.iter()
        .map(|v| v.to_string())
        .collect::<Vec<_>>()
        .join(",")
}

fn warn_key(uid: u32) -> String {
    uid.to_string()
}

fn render_targets(events: &[AttemptEvent]) -> String {
    let mut by_proto: BTreeMap<Proto, BTreeSet<u16>> = BTreeMap::new();
    for event in events {
        let entry = by_proto.entry(event.proto).or_default();
        for port in &event.dst_ports {
            entry.insert(*port);
        }
    }

    Proto::ALL
        .iter()
        .filter_map(|proto| {
            by_proto.get(proto).and_then(|ports| {
                if ports.is_empty() {
                    None
                } else {
                    Some(format!(
                        "{}:[{}]",
                        proto.short_label(),
                        render_ports(&ports.iter().copied().collect::<Vec<_>>())
                    ))
                }
            })
        })
        .collect::<Vec<_>>()
        .join("; ")
}

pub fn process_counters(
    cfg: &Config,
    state: &mut DetectorState,
    counters: &[RuleCounter],
    packages: &PackageIndex,
) {
    let now = now_secs();
    let mut reactions: Vec<(u32, Vec<String>)> = Vec::new();

    for counter in counters {
        let prev = state
            .last_seen_counters
            .get(&counter.tag)
            .cloned()
            .unwrap_or_default();

        let delta_packets = counter.packets.saturating_sub(prev.packets);
        let delta_bytes = counter.bytes.saturating_sub(prev.bytes);

        if delta_packets > 0 || delta_bytes > 0 {
            let event = AttemptEvent {
                ts: now,
                proto: counter.proto,
                dst_ports: counter.dst_ports.clone(),
                packets: delta_packets,
                bytes: delta_bytes,
                rule_hits: 1,
            };
            state
                .by_uid
                .entry(counter.blocked_uid)
                .or_default()
                .push(event);

            logger::info(
                "detector",
                &format!(
                    "blocked localhost probe: {} proto={} ports=[{}] packets+={} bytes+={}",
                    uid_label(packages, counter.blocked_uid),
                    counter.proto.short_label(),
                    render_ports(&counter.dst_ports),
                    delta_packets,
                    delta_bytes
                ),
            );
        }

        state.last_seen_counters.insert(
            counter.tag.clone(),
            CounterSnapshot {
                packets: counter.packets,
                bytes: counter.bytes,
            },
        );
    }

    prune_old_events(state, cfg.scan_window_secs, now);

    for (uid, events) in &state.by_uid {
        if events.is_empty() {
            continue;
        }

        if cfg.uid_is_trusted(*uid) {
            continue;
        }

        let uid_packages = packages.packages_for_uid(*uid);
        if uid_packages
            .iter()
            .any(|pkg| cfg.package_is_scan_ignored(pkg) || cfg.package_is_trusted(pkg))
        {
            continue;
        }

        let mut all_ports = BTreeSet::new();
        let mut exact_ports = BTreeSet::new();
        let mut rule_hits = 0_u64;
        let total_attempts: u64 = events.iter().map(|ev| ev.packets.max(1)).sum();
        let mut grouped_hits = 0_u64;

        for ev in events {
            rule_hits = rule_hits.saturating_add(ev.rule_hits.max(1));
            if ev.dst_ports.len() > 1 {
                grouped_hits = grouped_hits.saturating_add(1);
            }
            for port in &ev.dst_ports {
                all_ports.insert(*port);
            }
            if ev.dst_ports.len() == 1 {
                if let Some(port) = ev.dst_ports.first() {
                    exact_ports.insert(*port);
                }
            }
        }

        let suspicious = all_ports.len() >= cfg.suspicious_unique_ports
            || total_attempts >= cfg.suspicious_attempts
            || rule_hits >= cfg.suspicious_rule_hits;

        if !suspicious {
            continue;
        }

        let warn_key = warn_key(*uid);
        let last_warn = state.last_warn_epoch.get(&warn_key).copied().unwrap_or(0);
        if now.saturating_sub(last_warn) < cfg.warn_cooldown_secs {
            continue;
        }

        let window_ports: Vec<u16> = all_ports.iter().copied().collect();
        logger::warn(
            "detector",
            &format!(
                "possible localhost scan detected: {} unique_ports={} exact_ports={} total_attempts={} rule_hits={} grouped_hits={} window={}s ports=[{}] targets={} reaction_mode={}",
                uid_label(packages, *uid),
                all_ports.len(),
                exact_ports.len(),
                total_attempts,
                rule_hits,
                grouped_hits,
                cfg.scan_window_secs,
                render_ports(&window_ports),
                render_targets(events),
                cfg.reaction_mode
            ),
        );
        logger::info(
            "detector",
            &format!(
                "thresholds unique_ports>={} attempts>={} rule_hits>={} current_grouped_hits={}",
                cfg.suspicious_unique_ports,
                cfg.suspicious_attempts,
                cfg.suspicious_rule_hits,
                grouped_hits
            ),
        );
        state.last_warn_epoch.insert(warn_key, now);

        reactions.push((*uid, uid_packages));
    }

    for (uid, uid_packages) in reactions {
        reaction::maybe_force_stop(
            cfg,
            state,
            uid,
            &uid_packages,
            "localhost scan threshold reached",
        );
    }
}
