use crate::{
    config::Config,
    logger,
    reaction,
    runtime::state::{append_incident, AttemptEvent, CounterSnapshot, DetectorState, IncidentRecord},
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
    let current_tags = counters
        .iter()
        .map(|counter| counter.tag.clone())
        .collect::<BTreeSet<_>>();

    for counter in counters {
        let prev = state
            .last_seen_counters
            .get(&counter.tag)
            .cloned()
            .unwrap_or_default();

        // A/B chain replacement resets kernel counters. If the current value is
        // lower than our stored baseline, treat the current value as activity
        // since the reset instead of waiting for it to "catch up" to the old one.
        let reset = counter.packets < prev.packets || counter.bytes < prev.bytes;
        let delta_packets = if reset {
            counter.packets
        } else {
            counter.packets.saturating_sub(prev.packets)
        };
        let delta_bytes = if reset {
            counter.bytes
        } else {
            counter.bytes.saturating_sub(prev.bytes)
        };

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

            let target_kind = if counter.dst_ports.len() == 1 {
                "exact"
            } else {
                "grouped/ambiguous"
            };
            logger::info(
                "detector",
                &format!(
                    "blocked localhost probe: {} proto={} ports=[{}] target_kind={} packets+={} bytes+={} counter_reset={}",
                    uid_label(packages, counter.blocked_uid),
                    counter.proto.short_label(),
                    render_ports(&counter.dst_ports),
                    target_kind,
                    delta_packets,
                    delta_bytes,
                    reset,
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

    // Drop baselines for rules that no longer exist so detector_state.json does
    // not grow forever after policy/chain churn.
    state
        .last_seen_counters
        .retain(|tag, _| current_tags.contains(tag));

    prune_old_events(state, cfg.scan_window_secs, now);
    state.by_uid.retain(|_, events| !events.is_empty());

    // Cooldown maps are diagnostic/runtime state, not history. Bound their
    // lifetime so apps that disappeared months ago do not accumulate forever.
    let cooldown_retention = 86_400_u64.max(
        cfg.scan_window_secs
            .max(cfg.warn_cooldown_secs)
            .max(cfg.reaction_cooldown_secs)
            .saturating_mul(4),
    );
    state
        .last_warn_epoch
        .retain(|_, ts| now.saturating_sub(*ts) <= cooldown_retention);
    state
        .last_action_epoch
        .retain(|_, ts| now.saturating_sub(*ts) <= cooldown_retention);

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

        let mut potential_ports = BTreeSet::new();
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
                potential_ports.insert(*port);
            }
            if ev.dst_ports.len() == 1 {
                if let Some(port) = ev.dst_ports.first() {
                    exact_ports.insert(*port);
                }
            }
        }

        // Multiport counters only prove that one port in the group matched. They
        // must never inflate the unique-port scan threshold. Only one-port rules
        // contribute exact unique-port evidence.
        let unique_port_threshold = exact_ports.len() >= cfg.suspicious_unique_ports;
        let attempts_threshold = total_attempts >= cfg.suspicious_attempts;
        let rule_hits_threshold = rule_hits >= cfg.suspicious_rule_hits;
        let suspicious = unique_port_threshold || attempts_threshold || rule_hits_threshold;

        if !suspicious {
            continue;
        }

        let warn_key = warn_key(*uid);
        let last_warn = state.last_warn_epoch.get(&warn_key).copied().unwrap_or(0);
        if now.saturating_sub(last_warn) < cfg.warn_cooldown_secs {
            continue;
        }

        let potential_window_ports: Vec<u16> = potential_ports.iter().copied().collect();
        let exact_window_ports: Vec<u16> = exact_ports.iter().copied().collect();
        logger::warn(
            "detector",
            &format!(
                "possible localhost scan detected: {} exact_unique_ports={} potential_group_ports={} total_attempts={} rule_hits={} grouped_hits={} window={}s exact_ports=[{}] potential_ports=[{}] targets={} reaction_mode={}",
                uid_label(packages, *uid),
                exact_ports.len(),
                potential_ports.len(),
                total_attempts,
                rule_hits,
                grouped_hits,
                cfg.scan_window_secs,
                render_ports(&exact_window_ports),
                render_ports(&potential_window_ports),
                render_targets(events),
                cfg.reaction_mode
            ),
        );
        logger::info(
            "detector",
            &format!(
                "thresholds exact_unique_ports>={} attempts>={} rule_hits>={} current_grouped_hits={}",
                cfg.suspicious_unique_ports,
                cfg.suspicious_attempts,
                cfg.suspicious_rule_hits,
                grouped_hits
            ),
        );
        state.last_warn_epoch.insert(warn_key, now);
        let incident_ports = if exact_window_ports.is_empty() {
            potential_window_ports.clone()
        } else {
            exact_window_ports.clone()
        };
        if let Err(err) = append_incident(
            &cfg.state_dir,
            &IncidentRecord {
                timestamp: now,
                level: "WARN".to_string(),
                uid: *uid,
                packages: uid_packages.clone(),
                ports: incident_ports,
                action: "warn".to_string(),
                message: format!(
                    "possible localhost scan: exact_unique_ports={} attempts={} rule_hits={} grouped_hits={}",
                    exact_ports.len(), total_attempts, rule_hits, grouped_hits
                ),
            },
        ) {
            logger::warn("detector", &format!("incident write failed: {err:#}"));
        }

        // Destructive reaction requires stronger evidence than a warning. A
        // single grouped multiport hit can be ambiguous, so it may warn but it
        // cannot force-stop an app on its own.
        let reaction_confident = unique_port_threshold
            || (attempts_threshold && rule_hits >= cfg.suspicious_rule_hits.max(2));
        if reaction_confident {
            reactions.push((*uid, uid_packages));
        } else {
            logger::info(
                "detector",
                &format!(
                    "reaction suppressed for uid={}: evidence is ambiguous/grouped",
                    uid
                ),
            );
        }
    }

    for (uid, uid_packages) in reactions {
        reaction::maybe_force_stop(
            cfg,
            state,
            uid,
            &uid_packages,
            "localhost scan threshold reached with confirmed evidence",
        );
    }
}

