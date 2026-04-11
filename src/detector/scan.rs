use crate::{
    config::Config,
    logger,
    reaction,
    runtime::state::{AttemptEvent, CounterSnapshot, DetectorState},
    types::{PackageIndex, Proto, RuleCounter},
};
use std::collections::BTreeSet;

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

fn warn_key(uid: u32, proto: Proto) -> String {
    format!("{}:{}", uid, proto.short_label())
}

#[derive(Debug, Clone, Copy)]
struct ProtoThresholds {
    unique_ports: usize,
    total_attempts: u64,
    rule_hits: u64,
    min_signal_ports_for_attempts: usize,
    min_grouped_hits_for_attempts: u64,
}

fn thresholds_for_proto(cfg: &Config, proto: Proto) -> ProtoThresholds {
    match proto {
        Proto::Udp4 | Proto::Udp6 => ProtoThresholds {
            unique_ports: cfg.suspicious_unique_ports.max(2).saturating_add(1),
            total_attempts: cfg.suspicious_attempts.max(2).saturating_mul(2),
            rule_hits: cfg.suspicious_rule_hits.max(1).saturating_mul(2),
            min_signal_ports_for_attempts: cfg.suspicious_unique_ports.max(2),
            min_grouped_hits_for_attempts: 2,
        },
        Proto::Tcp4 | Proto::Tcp6 => ProtoThresholds {
            unique_ports: cfg.suspicious_unique_ports,
            total_attempts: cfg.suspicious_attempts,
            rule_hits: cfg.suspicious_rule_hits,
            min_signal_ports_for_attempts: 1,
            min_grouped_hits_for_attempts: 1,
        },
    }
}

fn render_targets_for_proto(events: &[&AttemptEvent], proto: Proto) -> String {
    let mut ports = BTreeSet::new();
    for event in events {
        for port in &event.dst_ports {
            ports.insert(*port);
        }
    }
    format!("{}:[{}]", proto.short_label(), render_ports(&ports.into_iter().collect::<Vec<_>>()))
}

pub fn process_counters(
    cfg: &Config,
    state: &mut DetectorState,
    counters: &[RuleCounter],
    packages: &PackageIndex,
) {
    let now = now_secs();
    let mut reactions: Vec<(u32, Vec<String>, Proto)> = Vec::new();

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

        for proto in Proto::ALL {
            let proto_events = events
                .iter()
                .filter(|ev| ev.proto == proto)
                .collect::<Vec<_>>();
            if proto_events.is_empty() {
                continue;
            }

            let mut all_ports = BTreeSet::new();
            let mut exact_ports = BTreeSet::new();
            let mut rule_hits = 0_u64;
            let total_attempts: u64 = proto_events.iter().map(|ev| ev.packets.max(1)).sum();
            let mut grouped_hits = 0_u64;

            for ev in &proto_events {
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

            let thresholds = thresholds_for_proto(cfg, proto);
            let attempts_signal = total_attempts >= thresholds.total_attempts
                && (all_ports.len() >= thresholds.min_signal_ports_for_attempts
                    || exact_ports.len() >= thresholds.min_signal_ports_for_attempts
                    || grouped_hits >= thresholds.min_grouped_hits_for_attempts);
            let suspicious = all_ports.len() >= thresholds.unique_ports
                || attempts_signal
                || rule_hits >= thresholds.rule_hits;

            if !suspicious {
                continue;
            }

            let warn_key = warn_key(*uid, proto);
            let last_warn = state.last_warn_epoch.get(&warn_key).copied().unwrap_or(0);
            if now.saturating_sub(last_warn) < cfg.warn_cooldown_secs {
                continue;
            }

            logger::warn(
                "detector",
                &format!(
                    "possible localhost scan detected: {} proto={} unique_ports={} exact_ports={} total_attempts={} rule_hits={} grouped_hits={} window={}s targets={} reaction_mode={}",
                    uid_label(packages, *uid),
                    proto.short_label(),
                    all_ports.len(),
                    exact_ports.len(),
                    total_attempts,
                    rule_hits,
                    grouped_hits,
                    cfg.scan_window_secs,
                    render_targets_for_proto(&proto_events, proto),
                    cfg.reaction_mode
                ),
            );
            logger::info(
                "detector",
                &format!(
                    "thresholds proto={} unique_ports>={} attempts>={} rule_hits>={} attempts_need_ports>={} attempts_need_grouped_hits>={}",
                    proto.short_label(),
                    thresholds.unique_ports,
                    thresholds.total_attempts,
                    thresholds.rule_hits,
                    thresholds.min_signal_ports_for_attempts,
                    thresholds.min_grouped_hits_for_attempts
                ),
            );
            state.last_warn_epoch.insert(warn_key, now);

            reactions.push((*uid, uid_packages.clone(), proto));
        }
    }

    for (uid, uid_packages, proto) in reactions {
        reaction::maybe_force_stop(
            cfg,
            state,
            uid,
            &uid_packages,
            &format!("localhost scan threshold reached for {}", proto.short_label()),
        );
    }
}
