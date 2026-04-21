use crate::{
    logger,
    shell,
    types::{
        AppliedRule, DesiredRule, FirewallProbeAttempt, FirewallProtoCapability, LocalAddressSnapshot, Proto, RuleCounter,
    },
};
use anyhow::{Context, Result};
use std::{
    collections::{hash_map::DefaultHasher, BTreeMap, BTreeSet},
    hash::{Hash, Hasher},
    path::Path,
    time::Duration,
};

const IPTABLES_WAIT_SECS: u64 = 20;
const XTABLES_TIMEOUT_MARGIN_SECS: u64 = 8;
const XTABLES_RETRY_COUNT: usize = 3;
const XTABLES_RETRY_DELAY_MS: u64 = 350;
const MULTIPORT_MAX_PORTS: usize = 15;
const MAX_SHELL_ERROR_CHARS: usize = 240;
const SELF_TEST_BASE_PORT: u16 = 45940;

#[derive(Debug, Clone)]
enum LocalMatchMode {
    DstTypeLocal,
    LoopbackIface,
    DestLoopback,
    LoAndDestLoopback,
    DestLocalAddressList,
}

impl LocalMatchMode {
    fn describe(&self, proto: Proto, local_addresses: &[String]) -> String {
        match self {
            Self::DstTypeLocal => "dst-type LOCAL".to_string(),
            Self::LoopbackIface => "-o lo".to_string(),
            Self::DestLoopback => format!("-d {}", proto.loopback_ip()),
            Self::LoAndDestLoopback => format!("-o lo -d {}", proto.loopback_ip()),
            Self::DestLocalAddressList => format!("local-ip-list({})", local_addresses.len()),
        }
    }

    fn hook_match_sets(&self, proto: Proto, local_addresses: &[String]) -> Vec<Vec<String>> {
        match self {
            Self::DstTypeLocal => vec![vec![
                "-m".to_string(),
                "addrtype".to_string(),
                "--dst-type".to_string(),
                "LOCAL".to_string(),
            ]],
            Self::LoopbackIface => vec![vec!["-o".to_string(), "lo".to_string()]],
            Self::DestLoopback => vec![vec!["-d".to_string(), proto.loopback_ip().to_string()]],
            Self::LoAndDestLoopback => vec![vec![
                "-o".to_string(),
                "lo".to_string(),
                "-d".to_string(),
                proto.loopback_ip().to_string(),
            ]],
            Self::DestLocalAddressList => local_addresses
                .iter()
                .cloned()
                .map(|ip| vec!["-d".to_string(), ip])
                .collect(),
        }
    }
}

#[derive(Debug, Clone, Copy)]
enum PortMatchMode {
    Multiport,
    PerPort,
}

impl PortMatchMode {
    fn describe(self) -> &'static str {
        match self {
            Self::Multiport => "multiport",
            Self::PerPort => "per-port",
        }
    }
}

#[derive(Debug, Clone)]
struct BackendMode {
    local: LocalMatchMode,
    ports: PortMatchMode,
}

impl BackendMode {
    fn describe(&self, proto: Proto, binary: &str, socket_exists: bool, local_addresses: &[String]) -> String {
        let socket = if socket_exists { " socket-exists" } else { "" };
        format!(
            "bin={} local={} ports={}{}",
            binary,
            self.local.describe(proto, local_addresses),
            self.ports.describe(),
            socket
        )
    }
}

#[derive(Debug, Clone)]
struct SelectedBackend {
    mode: BackendMode,
    use_socket_exists: bool,
    local_addresses: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ChainSlot {
    A,
    B,
}

impl ChainSlot {
    fn suffix(self) -> &'static str {
        match self {
            Self::A => "A",
            Self::B => "B",
        }
    }

    fn other(self) -> Self {
        match self {
            Self::A => Self::B,
            Self::B => Self::A,
        }
    }
}

#[derive(Debug, Clone)]
struct FamilyProbeCapability {
    xtables_binary: String,
    binary_available: bool,
    output_chain_readable: bool,
    chain_ops_ok: bool,
    owner_match_ok: bool,
    socket_exists_ok: bool,
    comment_match_ok: bool,
    multiport_ok: bool,
    last_error: String,
}

impl FamilyProbeCapability {
    fn is_ready(&self) -> bool {
        self.binary_available
            && self.output_chain_readable
            && self.chain_ops_ok
            && self.owner_match_ok
            && self.comment_match_ok
    }
}

#[derive(Debug, Clone)]
pub struct ApplyReport {
    pub actual_rule_count: usize,
    pub backend: String,
    pub applied_rules: Vec<AppliedRule>,
    pub capabilities: Vec<FirewallProtoCapability>,
}

#[derive(Debug, Clone)]
struct ProtoApplyReport {
    actual_rule_count: usize,
    backend: String,
    chain_name: String,
    applied_rules: Vec<AppliedRule>,
}

fn summarize_shell_text(text: &str) -> String {
    let compact = text.split_whitespace().collect::<Vec<_>>().join(" ");
    if compact.is_empty() {
        return String::new();
    }
    if compact.chars().count() > MAX_SHELL_ERROR_CHARS {
        compact.chars().take(MAX_SHELL_ERROR_CHARS).collect::<String>() + "..."
    } else {
        compact
    }
}

fn candidate_modes(has_local_addresses: bool) -> Vec<BackendMode> {
    let mut out = vec![
        BackendMode {
            local: LocalMatchMode::DstTypeLocal,
            ports: PortMatchMode::Multiport,
        },
        BackendMode {
            local: LocalMatchMode::LoopbackIface,
            ports: PortMatchMode::Multiport,
        },
        BackendMode {
            local: LocalMatchMode::DestLoopback,
            ports: PortMatchMode::Multiport,
        },
        BackendMode {
            local: LocalMatchMode::LoAndDestLoopback,
            ports: PortMatchMode::Multiport,
        },
        BackendMode {
            local: LocalMatchMode::DstTypeLocal,
            ports: PortMatchMode::PerPort,
        },
        BackendMode {
            local: LocalMatchMode::LoopbackIface,
            ports: PortMatchMode::PerPort,
        },
        BackendMode {
            local: LocalMatchMode::DestLoopback,
            ports: PortMatchMode::PerPort,
        },
        BackendMode {
            local: LocalMatchMode::LoAndDestLoopback,
            ports: PortMatchMode::PerPort,
        },
    ];

    if has_local_addresses {
        out.insert(
            1,
            BackendMode {
                local: LocalMatchMode::DestLocalAddressList,
                ports: PortMatchMode::Multiport,
            },
        );
        out.push(BackendMode {
            local: LocalMatchMode::DestLocalAddressList,
            ports: PortMatchMode::PerPort,
        });
    }

    out
}

fn family_sample_proto(proto: Proto) -> Proto {
    if proto.is_ipv6() {
        Proto::Tcp6
    } else {
        Proto::Tcp4
    }
}

fn binary_basename(proto: Proto) -> &'static str {
    match proto {
        Proto::Tcp4 | Proto::Udp4 => "iptables",
        Proto::Tcp6 | Proto::Udp6 => "ip6tables",
    }
}

fn resolve_xtables_binary(proto: Proto) -> String {
    binary_basename(proto).to_string()
}

fn is_xtables_timeout(err_text: &str) -> bool {
    err_text.contains("command timed out after")
}

fn xtables_timeout() -> Duration {
    Duration::from_secs(IPTABLES_WAIT_SECS + XTABLES_TIMEOUT_MARGIN_SECS)
}

fn run_xtables_owned(binary: &str, args: Vec<String>) -> Result<(i32, String)> {
    let timeout = xtables_timeout();
    let mut last_err: Option<anyhow::Error> = None;

    for attempt in 0..XTABLES_RETRY_COUNT {
        let refs = args.iter().map(|v| v.as_str()).collect::<Vec<_>>();
        match shell::run_with_timeout(binary, &refs, timeout) {
            Ok(result) => return Ok(result),
            Err(err) => {
                let err_text = format!("{err:#}");
                let retryable = is_xtables_timeout(&err_text);
                last_err = Some(err);
                if retryable && attempt + 1 < XTABLES_RETRY_COUNT {
                    std::thread::sleep(Duration::from_millis(XTABLES_RETRY_DELAY_MS));
                    continue;
                }
                break;
            }
        }
    }

    Err(last_err.unwrap_or_else(|| anyhow::anyhow!("xtables execution failed")))
}

fn xtables(binary: &str, args: &[&str]) -> Result<(i32, String)> {
    let mut full = vec!["-w".to_string(), IPTABLES_WAIT_SECS.to_string()];
    full.extend(args.iter().map(|v| (*v).to_string()));
    run_xtables_owned(binary, full)
}

fn xtablesv(binary: &str, args: &[String]) -> Result<(i32, String)> {
    let mut full = vec!["-w".to_string(), IPTABLES_WAIT_SECS.to_string()];
    full.extend(args.iter().cloned());
    run_xtables_owned(binary, full)
}

fn managed_chain_name(base_chain: &str, proto: Proto, slot: ChainSlot) -> String {
    format!("{}_{}_{}", base_chain, proto.chain_suffix(), slot.suffix())
}

fn legacy_chain_name(base_chain: &str, proto: Proto) -> String {
    format!("{}_{}", base_chain, proto.chain_suffix())
}

fn probe_chain_name(base_chain: &str, proto: Proto) -> String {
    format!("{}_P{}", base_chain, proto.chain_suffix())
}

fn ip_binary_candidates() -> Vec<String> {
    [
        "/system/bin/ip".to_string(),
        "/system/xbin/ip".to_string(),
        "/vendor/bin/ip".to_string(),
        "ip".to_string(),
    ]
    .to_vec()
}

fn resolve_ip_binary() -> Option<String> {
    let candidates = ip_binary_candidates();
    for candidate in &candidates {
        if candidate.contains('/') && Path::new(candidate).exists() {
            return Some(candidate.clone());
        }
    }
    candidates.last().cloned()
}

fn collect_local_addresses(proto: Proto) -> Vec<String> {
    let Some(ip_binary) = resolve_ip_binary() else {
        return vec![proto.loopback_ip().to_string()];
    };

    let family_flag = if proto.is_ipv6() { "-6" } else { "-4" };
    let Ok((rc, out)) = shell::run(&ip_binary, &["-o", family_flag, "addr", "show", "up"]) else {
        return vec![proto.loopback_ip().to_string()];
    };
    if rc != 0 {
        return vec![proto.loopback_ip().to_string()];
    }

    let mut values = BTreeSet::new();
    values.insert(proto.loopback_ip().to_string());

    for line in out.lines() {
        let cols = line.split_whitespace().collect::<Vec<_>>();
        for marker in ["inet", "inet6"] {
            if let Some(pos) = cols.iter().position(|v| *v == marker) {
                if let Some(value) = cols.get(pos + 1) {
                    let ip = value.split('/').next().unwrap_or("").trim();
                    if !ip.is_empty() && ip != "0.0.0.0" && ip != "::" {
                        values.insert(ip.to_string());
                    }
                }
            }
        }
    }

    values.into_iter().collect()
}

pub fn current_local_address_snapshots(protos: &[Proto]) -> Vec<LocalAddressSnapshot> {
    let mut seen = BTreeSet::new();
    let mut out = Vec::new();

    for proto in protos {
        if !seen.insert(*proto) {
            continue;
        }
        out.push(LocalAddressSnapshot {
            proto: *proto,
            addresses: collect_local_addresses(*proto),
        });
    }

    out.sort_by_key(|item| item.proto);
    out
}

fn hook_variants(proto: Proto, chain: &str, local_mode: &LocalMatchMode, local_addresses: &[String]) -> Vec<Vec<String>> {
    local_mode
        .hook_match_sets(proto, local_addresses)
        .into_iter()
        .map(|match_args| {
            let mut args = vec![
                "OUTPUT".to_string(),
                "-p".to_string(),
                proto.l4_label().to_string(),
            ];
            args.extend(match_args);
            args.extend(["-j".to_string(), chain.to_string()]);
            args
        })
        .collect()
}

fn parse_output_rules_for_chain(proto: Proto, chain: &str) -> Result<Vec<Vec<String>>> {
    let binary = resolve_xtables_binary(proto);
    let (rc, text) = xtables(&binary, &["-S", "OUTPUT"])?;
    if rc != 0 {
        anyhow::bail!(
            "{} -S OUTPUT failed: {}",
            binary,
            summarize_shell_text(text.trim())
        );
    }

    let mut out = Vec::new();
    for line in text.lines() {
        let trimmed = line.trim();
        if !trimmed.starts_with("-A OUTPUT ") {
            continue;
        }
        let cols = trimmed.split_whitespace().collect::<Vec<_>>();
        let targets_chain = cols
            .windows(2)
            .any(|pair| pair[0] == "-j" && pair[1] == chain);
        if !targets_chain {
            continue;
        }
        let tokens = cols.iter().skip(2).map(|v| (*v).to_string()).collect::<Vec<_>>();
        if !tokens.is_empty() {
            out.push(tokens);
        }
    }
    Ok(out)
}

fn active_slot(proto: Proto, base_chain: &str) -> Result<Option<ChainSlot>> {
    let a_chain = managed_chain_name(base_chain, proto, ChainSlot::A);
    let b_chain = managed_chain_name(base_chain, proto, ChainSlot::B);
    let a_rules = parse_output_rules_for_chain(proto, &a_chain)?;
    let b_rules = parse_output_rules_for_chain(proto, &b_chain)?;

    if !b_rules.is_empty() {
        return Ok(Some(ChainSlot::B));
    }
    if !a_rules.is_empty() {
        return Ok(Some(ChainSlot::A));
    }
    Ok(None)
}

fn ensure_chain_exists(proto: Proto, chain: &str) -> Result<()> {
    let binary = resolve_xtables_binary(proto);
    let (rc, _) = xtables(&binary, &["-L", chain])?;
    if rc != 0 {
        let (rc, out) = xtables(&binary, &["-N", chain])?;
        if rc != 0 {
            anyhow::bail!("{} -N {} failed: {}", binary, chain, summarize_shell_text(&out));
        }
    }
    Ok(())
}

fn remove_all_hooks_to_chain(proto: Proto, chain: &str) -> Result<()> {
    let binary = resolve_xtables_binary(proto);
    loop {
        let matches = parse_output_rules_for_chain(proto, chain)?;
        let Some(rule) = matches.into_iter().next() else {
            break;
        };
        let mut args = vec!["-D".to_string(), "OUTPUT".to_string()];
        args.extend(rule);
        let (rc, _) = xtablesv(&binary, &args)?;
        if rc != 0 {
            break;
        }
    }
    Ok(())
}

fn ensure_hooks(proto: Proto, chain: &str, selected: &SelectedBackend) -> Result<()> {
    let binary = resolve_xtables_binary(proto);
    for rule in hook_variants(proto, chain, &selected.mode.local, &selected.local_addresses) {
        let mut check = vec!["-C".to_string()];
        check.extend(rule.clone());
        let check_refs: Vec<&str> = check.iter().map(|v| v.as_str()).collect();
        let (rc, _) = xtables(&binary, &check_refs)?;
        if rc == 0 {
            continue;
        }

        let mut install = vec!["-I".to_string()];
        install.extend(rule);
        let install_refs: Vec<&str> = install.iter().map(|v| v.as_str()).collect();
        let (rc, out) = xtables(&binary, &install_refs)?;
        if rc != 0 {
            anyhow::bail!("{} hook install failed: {}", binary, summarize_shell_text(&out));
        }
    }
    Ok(())
}

fn clear_single_chain(proto: Proto, chain: &str) -> Result<()> {
    let binary = resolve_xtables_binary(proto);
    let _ = remove_all_hooks_to_chain(proto, chain);
    let _ = xtables(&binary, &["-F", chain]);
    let _ = xtables(&binary, &["-X", chain]);
    Ok(())
}

fn clear_probe_chain(proto: Proto, chain: &str) {
    let binary = resolve_xtables_binary(proto);
    let _ = remove_all_hooks_to_chain(proto, chain);
    let _ = xtables(&binary, &["-F", chain]);
    let _ = xtables(&binary, &["-X", chain]);
}

pub fn clear_chain(base_chain: &str) -> Result<()> {
    for proto in Proto::ALL {
        let _ = clear_single_chain(proto, &legacy_chain_name(base_chain, proto));
        clear_single_chain(proto, &managed_chain_name(base_chain, proto, ChainSlot::A))?;
        clear_single_chain(proto, &managed_chain_name(base_chain, proto, ChainSlot::B))?;
    }
    Ok(())
}

fn render_comment(rule: &AppliedRule) -> String {
    let mut hasher = DefaultHasher::new();
    rule.proto.hash(&mut hasher);
    rule.blocked_uid.hash(&mut hasher);
    rule.dst_ports.hash(&mut hasher);
    rule.owner_uids.hash(&mut hasher);
    let digest = hasher.finish();
    format!(
        "PG {} u={} n={} h={:016x}",
        rule.proto.short_label(),
        rule.blocked_uid,
        rule.dst_ports.len(),
        digest
    )
}

fn append_owner_comment_args(args: &mut Vec<String>, uid: u32, comment: &str, socket_exists: bool) {
    args.extend([
        "-m".to_string(),
        "owner".to_string(),
        "--uid-owner".to_string(),
        uid.to_string(),
    ]);
    if socket_exists {
        args.push("--socket-exists".to_string());
    }
    args.extend([
        "-m".to_string(),
        "comment".to_string(),
        "--comment".to_string(),
        comment.to_string(),
    ]);
}

fn append_port_args(args: &mut Vec<String>, mode: PortMatchMode, ports: &[u16]) {
    match mode {
        PortMatchMode::Multiport => {
            args.extend([
                "-m".to_string(),
                "multiport".to_string(),
                "--dports".to_string(),
                ports.iter()
                    .map(|v| v.to_string())
                    .collect::<Vec<_>>()
                    .join(","),
            ]);
        }
        PortMatchMode::PerPort => {
            let port = ports.first().copied().unwrap_or_default();
            args.extend(["--dport".to_string(), port.to_string()]);
        }
    }
}

fn build_probe_rule_args(
    proto: Proto,
    chain_name: &str,
    ports: &[u16],
    mode: PortMatchMode,
    socket_exists: bool,
) -> Vec<String> {
    let mut args = vec![
        "-A".to_string(),
        chain_name.to_string(),
        "-p".to_string(),
        proto.l4_label().to_string(),
    ];

    append_owner_comment_args(&mut args, 0, "PG probe", socket_exists);
    append_port_args(&mut args, mode, ports);
    args.extend(["-j".to_string(), "RETURN".to_string()]);
    args
}

fn add_rule(rule: &AppliedRule, selected: &SelectedBackend) -> Result<()> {
    let comment = render_comment(rule);
    let mut args = vec![
        "-A".to_string(),
        rule.chain_name.clone(),
        "-p".to_string(),
        rule.proto.l4_label().to_string(),
    ];

    append_owner_comment_args(&mut args, rule.blocked_uid, &comment, selected.use_socket_exists);
    append_port_args(&mut args, selected.mode.ports, &rule.dst_ports);
    args.extend(["-j".to_string(), "DROP".to_string()]);

    let binary = resolve_xtables_binary(rule.proto);
    let (rc, out) = xtablesv(&binary, &args)?;
    if rc != 0 {
        anyhow::bail!(
            "{} add rule failed ({}): {}",
            binary,
            selected
                .mode
                .describe(rule.proto, &binary, selected.use_socket_exists, &selected.local_addresses),
            summarize_shell_text(out.trim())
        );
    }
    Ok(())
}

fn chunk_ports(sorted_ports: &[u16], size: usize) -> Vec<Vec<u16>> {
    if size == 0 {
        return Vec::new();
    }
    sorted_ports.chunks(size).map(|c| c.to_vec()).collect()
}

fn group_rules(
    rules: &[DesiredRule],
    proto: Proto,
    chain_name: &str,
    backend: &str,
    mode: PortMatchMode,
) -> Vec<AppliedRule> {
    let mut by_uid: BTreeMap<u32, BTreeMap<u16, BTreeSet<u32>>> = BTreeMap::new();
    for rule in rules {
        by_uid
            .entry(rule.blocked_uid)
            .or_default()
            .entry(rule.dst_port)
            .or_default()
            .insert(rule.owner_uid);
    }

    let chunk_size = match mode {
        PortMatchMode::Multiport => MULTIPORT_MAX_PORTS,
        PortMatchMode::PerPort => 1,
    };

    let mut out = Vec::new();
    for (blocked_uid, ports_to_owners) in by_uid {
        let ports: Vec<u16> = ports_to_owners.keys().copied().collect();
        for chunk in chunk_ports(&ports, chunk_size) {
            let mut owner_uids = BTreeSet::new();
            for port in &chunk {
                if let Some(owners) = ports_to_owners.get(port) {
                    owner_uids.extend(owners.iter().copied());
                }
            }
            out.push(AppliedRule {
                chain_name: chain_name.to_string(),
                proto,
                blocked_uid,
                dst_ports: chunk,
                owner_uids: owner_uids.into_iter().collect(),
                backend: backend.to_string(),
            });
        }
    }

    out
}

fn ensure_probe_chain(proto: Proto, chain_name: &str) -> Result<()> {
    let binary = resolve_xtables_binary(proto);
    clear_probe_chain(proto, chain_name);
    let (rc, out) = xtables(&binary, &["-N", chain_name])?;
    if rc != 0 {
        anyhow::bail!(
            "{} probe chain create failed for {}: {}",
            binary,
            chain_name,
            summarize_shell_text(out.trim())
        );
    }
    Ok(())
}

fn run_probe_rule(proto: Proto, chain_name: &str, args: &[String], label: &str) -> Result<()> {
    let binary = resolve_xtables_binary(proto);
    let (rc, out) = xtablesv(&binary, args)?;
    if rc != 0 {
        anyhow::bail!(
            "{} {} failed: {}",
            binary,
            label,
            summarize_shell_text(out.trim())
        );
    }

    let (rc, out) = xtables(&binary, &["-D", chain_name, "1"])?;
    if rc != 0 {
        anyhow::bail!(
            "{} {} cleanup failed: {}",
            binary,
            label,
            summarize_shell_text(out.trim())
        );
    }
    Ok(())
}

fn probe_owner_comment(proto: Proto, chain_name: &str) -> Result<()> {
    let args = build_probe_rule_args(
        proto,
        chain_name,
        &[9],
        PortMatchMode::PerPort,
        false,
    );
    run_probe_rule(proto, chain_name, &args, "owner/comment probe")
}

fn probe_socket_exists(proto: Proto, chain_name: &str) -> Result<()> {
    let args = build_probe_rule_args(
        proto,
        chain_name,
        &[9],
        PortMatchMode::PerPort,
        true,
    );
    run_probe_rule(proto, chain_name, &args, "socket-exists probe")
}

fn probe_multiport(proto: Proto, chain_name: &str) -> Result<()> {
    let args = build_probe_rule_args(
        proto,
        chain_name,
        &[9, 10],
        PortMatchMode::Multiport,
        false,
    );
    run_probe_rule(proto, chain_name, &args, "multiport probe")
}

fn probe_hook_mode(
    proto: Proto,
    chain_name: &str,
    mode: &BackendMode,
    local_addresses: &[String],
) -> Result<()> {
    let selected = SelectedBackend {
        mode: mode.clone(),
        use_socket_exists: false,
        local_addresses: local_addresses.to_vec(),
    };
    ensure_hooks(proto, chain_name, &selected)?;
    remove_all_hooks_to_chain(proto, chain_name)?;
    Ok(())
}

fn probe_candidate_mode(
    proto: Proto,
    chain_name: &str,
    mode: &BackendMode,
    socket_exists: bool,
    local_addresses: &[String],
) -> Result<()> {
    let probe_ports = match mode.ports {
        PortMatchMode::Multiport => vec![9, 10],
        PortMatchMode::PerPort => vec![9],
    };
    let args = build_probe_rule_args(proto, chain_name, &probe_ports, mode.ports, socket_exists);
    let binary = resolve_xtables_binary(proto);
    let label = format!(
        "backend probe {}",
        mode.describe(proto, &binary, socket_exists, local_addresses)
    );
    run_probe_rule(proto, chain_name, &args, &label)?;
    probe_hook_mode(proto, chain_name, mode, local_addresses)
}

fn probe_family_capability(base_chain: &str, proto: Proto) -> FamilyProbeCapability {
    let chain_name = probe_chain_name(base_chain, proto);
    let binary = resolve_xtables_binary(proto);
    let mut capability = FamilyProbeCapability {
        xtables_binary: binary.clone(),
        binary_available: false,
        output_chain_readable: false,
        chain_ops_ok: false,
        owner_match_ok: false,
        socket_exists_ok: false,
        comment_match_ok: false,
        multiport_ok: false,
        last_error: String::new(),
    };

    match xtables(&binary, &["-S", "OUTPUT"]) {
        Ok((rc, out)) => {
            capability.binary_available = true;
            if rc != 0 {
                capability.last_error = format!(
                    "{} OUTPUT spec read failed: {}",
                    binary,
                    summarize_shell_text(out.trim())
                );
                return capability;
            }
            capability.output_chain_readable = true;
        }
        Err(err) => {
            let err_text = format!("{err:#}");
            capability.last_error = if is_xtables_timeout(&err_text) {
                format!("{} busy or timed out: {}", binary, err_text)
            } else {
                format!("{} unavailable: {}", binary, err_text)
            };
            return capability;
        }
    }

    match ensure_probe_chain(proto, &chain_name) {
        Ok(()) => capability.chain_ops_ok = true,
        Err(err) => {
            capability.last_error = format!("probe chain setup failed: {err:#}");
            return capability;
        }
    }

    match probe_owner_comment(proto, &chain_name) {
        Ok(()) => {
            capability.owner_match_ok = true;
            capability.comment_match_ok = true;
        }
        Err(err) => {
            capability.last_error = format!("owner/comment unsupported: {err:#}");
            clear_probe_chain(proto, &chain_name);
            return capability;
        }
    }

    if let Ok(()) = probe_socket_exists(proto, &chain_name) {
        capability.socket_exists_ok = true;
    }

    if let Ok(()) = probe_multiport(proto, &chain_name) {
        capability.multiport_ok = true;
    }

    clear_probe_chain(proto, &chain_name);
    capability
}

fn probe_proto_capability(
    base_chain: &str,
    proto: Proto,
    enabled_in_config: bool,
    rules_requested: usize,
    family_probe: &FamilyProbeCapability,
) -> (FirewallProtoCapability, Option<SelectedBackend>) {
    let chain_name = probe_chain_name(base_chain, proto);
    let binary = resolve_xtables_binary(proto);
    let mut capability = FirewallProtoCapability {
        proto,
        xtables_binary: family_probe.xtables_binary.clone(),
        chain_name: managed_chain_name(base_chain, proto, ChainSlot::A),
        enabled_in_config,
        rules_requested,
        binary_available: family_probe.binary_available,
        output_chain_readable: family_probe.output_chain_readable,
        chain_ops_ok: family_probe.chain_ops_ok,
        owner_match_ok: family_probe.owner_match_ok,
        socket_exists_ok: family_probe.socket_exists_ok,
        comment_match_ok: family_probe.comment_match_ok,
        multiport_ok: family_probe.multiport_ok,
        supported: false,
        selected_backend: String::new(),
        applied_rule_count: 0,
        active_self_test_attempted: false,
        active_self_test_ok: false,
        active_self_test_error: String::new(),
        active_self_test_packets: 0,
        last_error: family_probe.last_error.clone(),
        attempts: Vec::new(),
    };

    if !family_probe.is_ready() {
        return (capability, None);
    }

    match ensure_probe_chain(proto, &chain_name) {
        Ok(()) => capability.chain_ops_ok = true,
        Err(err) => {
            capability.last_error = format!("probe chain setup failed: {err:#}");
            return (capability, None);
        }
    }

    let local_addresses = collect_local_addresses(proto);
    let has_local_addresses = !local_addresses.is_empty();

    let mut selected_backend = None;
    for mode in candidate_modes(has_local_addresses) {
        for use_socket_exists in [capability.socket_exists_ok, false] {
            if use_socket_exists && !capability.socket_exists_ok {
                continue;
            }
            match probe_candidate_mode(proto, &chain_name, &mode, use_socket_exists, &local_addresses) {
                Ok(()) => {
                    let backend = mode.describe(proto, &binary, use_socket_exists, &local_addresses);
                    capability.attempts.push(FirewallProbeAttempt {
                        backend: backend.clone(),
                        ok: true,
                        error: String::new(),
                    });
                    if selected_backend.is_none() {
                        capability.supported = true;
                        capability.selected_backend = backend;
                        selected_backend = Some(SelectedBackend {
                            mode: mode.clone(),
                            use_socket_exists,
                            local_addresses: local_addresses.clone(),
                        });
                    }
                    break;
                }
                Err(err) => {
                    let error_text = format!("{err:#}");
                    capability.attempts.push(FirewallProbeAttempt {
                        backend: mode.describe(proto, &binary, use_socket_exists, &local_addresses),
                        ok: false,
                        error: error_text.clone(),
                    });
                    if capability.last_error.is_empty() {
                        capability.last_error = error_text;
                    }
                }
            }
        }
    }

    if !capability.supported && capability.last_error.is_empty() {
        capability.last_error = "no supported local/backend mode found".to_string();
    }

    clear_probe_chain(proto, &chain_name);
    (capability, selected_backend)
}

fn apply_rules_with_mode(
    base_chain: &str,
    desired_rules: &[DesiredRule],
    proto: Proto,
    selected: SelectedBackend,
) -> Result<ProtoApplyReport> {
    let active = active_slot(proto, base_chain)?;
    let target_slot = active.map(|slot| slot.other()).unwrap_or(ChainSlot::A);
    let target_chain = managed_chain_name(base_chain, proto, target_slot);
    let stale_chain = managed_chain_name(base_chain, proto, target_slot.other());
    let legacy_chain = legacy_chain_name(base_chain, proto);
    let binary = resolve_xtables_binary(proto);
    let backend = selected
        .mode
        .describe(proto, &binary, selected.use_socket_exists, &selected.local_addresses);
    let grouped = group_rules(
        desired_rules,
        proto,
        &target_chain,
        &backend,
        selected.mode.ports,
    );

    let _ = remove_all_hooks_to_chain(proto, &target_chain);
    ensure_chain_exists(proto, &target_chain)?;
    let (rc, out) = xtables(&binary, &["-F", &target_chain])?;
    if rc != 0 {
        anyhow::bail!(
            "{} -F {} failed: {}",
            binary,
            target_chain,
            summarize_shell_text(out.trim())
        );
    }

    for rule in &grouped {
        add_rule(rule, &selected)?;
    }

    if let Err(err) = ensure_hooks(proto, &target_chain, &selected) {
        let _ = remove_all_hooks_to_chain(proto, &target_chain);
        return Err(err);
    }

    let _ = remove_all_hooks_to_chain(proto, &legacy_chain);
    let _ = clear_single_chain(proto, &legacy_chain);
    let _ = remove_all_hooks_to_chain(proto, &stale_chain);
    if stale_chain != target_chain {
        let _ = clear_single_chain(proto, &stale_chain);
    }

    Ok(ProtoApplyReport {
        actual_rule_count: grouped.len(),
        backend,
        chain_name: target_chain,
        applied_rules: grouped,
    })
}

pub fn apply_rules(base_chain: &str, desired_rules: &[DesiredRule]) -> Result<ApplyReport> {
    let mut total_rules = 0_usize;
    let mut applied_rules = Vec::new();
    let mut backend_parts = Vec::new();
    let mut capabilities = Vec::new();
    let mut family_cache: BTreeMap<bool, FamilyProbeCapability> = BTreeMap::new();

    for proto in Proto::ALL {
        let proto_rules = desired_rules
            .iter()
            .filter(|rule| rule.proto == proto)
            .cloned()
            .collect::<Vec<_>>();
        let rules_requested = proto_rules.len();
        let enabled_in_config = rules_requested > 0;
        let family_key = proto.is_ipv6();
        let family_probe = family_cache
            .entry(family_key)
            .or_insert_with(|| probe_family_capability(base_chain, family_sample_proto(proto)))
            .clone();
        let (mut capability, selected_backend) =
            probe_proto_capability(base_chain, proto, enabled_in_config, rules_requested, &family_probe);

        if proto_rules.is_empty() {
            let _ = clear_single_chain(proto, &legacy_chain_name(base_chain, proto));
            let _ = clear_single_chain(proto, &managed_chain_name(base_chain, proto, ChainSlot::A));
            let _ = clear_single_chain(proto, &managed_chain_name(base_chain, proto, ChainSlot::B));
            if capability.supported {
                backend_parts.push(format!(
                    "{}[idle:{}]",
                    proto.short_label(),
                    capability.selected_backend
                ));
            } else if capability.last_error.is_empty() {
                backend_parts.push(format!("{}[idle-unsupported]", proto.short_label()));
            } else {
                backend_parts.push(format!(
                    "{}[idle-unsupported:{}]",
                    proto.short_label(),
                    summarize_shell_text(&capability.last_error)
                ));
            }
            capabilities.push(capability);
            continue;
        }

        if let Some(selected) = selected_backend {
            match apply_rules_with_mode(base_chain, &proto_rules, proto, selected) {
                Ok(report) => {
                    total_rules = total_rules.saturating_add(report.actual_rule_count);
                    capability.applied_rule_count = report.actual_rule_count;
                    capability.supported = true;
                    capability.selected_backend = report.backend.clone();
                    capability.chain_name = report.chain_name.clone();
                    backend_parts.push(format!("{}[{}]", proto.short_label(), report.backend));
                    applied_rules.extend(report.applied_rules);
                }
                Err(err) => {
                    capability.last_error = format!("apply failed: {err:#}");
                    backend_parts.push(format!(
                        "{}[apply-failed:{}]",
                        proto.short_label(),
                        summarize_shell_text(&capability.last_error)
                    ));
                    logger::warn(
                        "firewall",
                        &format!(
                            "skipping proto={} after apply failure: {}",
                            proto.short_label(),
                            capability.last_error
                        ),
                    );
                }
            }
        } else {
            let reason = if capability.last_error.is_empty() {
                "unsupported backend".to_string()
            } else {
                capability.last_error.clone()
            };
            backend_parts.push(format!(
                "{}[unsupported:{}]",
                proto.short_label(),
                summarize_shell_text(&reason)
            ));
            logger::warn(
                "firewall",
                &format!(
                    "skipping unsupported proto={} requested_rules={} reason={}",
                    proto.short_label(),
                    rules_requested,
                    reason
                ),
            );
        }

        capabilities.push(capability);
    }

    Ok(ApplyReport {
        actual_rule_count: total_rules,
        backend: backend_parts.join("; "),
        applied_rules,
        capabilities,
    })
}

fn parse_rule_listing_counters(line: &str) -> Option<(usize, u64, u64)> {
    let cols = line.split_whitespace().collect::<Vec<_>>();
    if cols.len() < 4 {
        return None;
    }

    let line_no = cols.get(0)?.parse::<usize>().ok()?;
    let packets = cols.get(1)?.parse::<u64>().ok()?;
    let bytes = cols.get(2)?.parse::<u64>().ok()?;
    Some((line_no, packets, bytes))
}

fn resolve_shell_binary() -> String {
    for candidate in ["/system/bin/sh", "/bin/sh", "sh"] {
        if !candidate.contains('/') || Path::new(candidate).exists() {
            return candidate.to_string();
        }
    }
    "sh".to_string()
}

fn resolve_nc_command() -> Option<String> {
    for candidate in [
        "/system/bin/nc",
        "/system/xbin/nc",
        "/vendor/bin/nc",
        "/bin/nc",
    ] {
        if Path::new(candidate).exists() {
            return Some(candidate.to_string());
        }
    }

    for candidate in [
        "/system/bin/toybox",
        "/vendor/bin/toybox",
        "/bin/toybox",
    ] {
        if Path::new(candidate).exists() {
            return Some(format!("{candidate} nc"));
        }
    }

    if shell::run("nc", &["-h"]).is_ok() {
        return Some("nc".to_string());
    }
    if shell::run("toybox", &["nc", "-h"]).is_ok() {
        return Some("toybox nc".to_string());
    }
    None
}

fn resolve_self_test_uid() -> u32 {
    let shell_binary = resolve_shell_binary();
    match shell::run(&shell_binary, &["-c", "id -u 2>/dev/null || echo 0"]) {
        Ok((_, out)) => out.trim().parse::<u32>().unwrap_or(0),
        Err(_) => 0,
    }
}

fn self_test_target_port(proto: Proto) -> u16 {
    match proto {
        Proto::Tcp4 => SELF_TEST_BASE_PORT,
        Proto::Udp4 => SELF_TEST_BASE_PORT + 1,
        Proto::Tcp6 => SELF_TEST_BASE_PORT + 20,
        Proto::Udp6 => SELF_TEST_BASE_PORT + 21,
    }
}

fn build_self_test_rule_args(
    capability: &FirewallProtoCapability,
    uid: u32,
    port: u16,
    comment: &str,
) -> Vec<String> {
    let mut args = vec![
        "-I".to_string(),
        capability.chain_name.clone(),
        "1".to_string(),
        "-p".to_string(),
        capability.proto.l4_label().to_string(),
        "-m".to_string(),
        "owner".to_string(),
        "--uid-owner".to_string(),
        uid.to_string(),
    ];
    if capability.socket_exists_ok {
        args.push("--socket-exists".to_string());
    }
    args.extend([
        "-m".to_string(),
        "comment".to_string(),
        "--comment".to_string(),
        comment.to_string(),
    ]);
    append_port_args(&mut args, PortMatchMode::PerPort, &[port]);
    args.extend(["-j".to_string(), "DROP".to_string()]);
    args
}

fn read_chain_line_counter(proto: Proto, chain_name: &str, line_no: usize) -> Result<(u64, u64)> {
    let binary = resolve_xtables_binary(proto);
    let (rc, text) = xtables(&binary, &["-L", chain_name, "-n", "-v", "-x", "--line-numbers"])?;
    if rc != 0 {
        anyhow::bail!(
            "{} list counters failed for {}: {}",
            binary,
            chain_name,
            summarize_shell_text(text.trim())
        );
    }

    for line in text.lines() {
        let Some((current_line, packets, bytes)) = parse_rule_listing_counters(line) else {
            continue;
        };
        if current_line == line_no {
            return Ok((packets, bytes));
        }
    }

    anyhow::bail!("line {} not found in chain {}", line_no, chain_name)
}

fn run_self_test_command(proto: Proto, target_ip: &str, target_port: u16, timeout_ms: u64) -> (String, String) {
    let Some(nc_command) = resolve_nc_command() else {
        return (String::new(), "nc/toybox nc not available".to_string());
    };

    let shell_binary = resolve_shell_binary();
    let command = match proto {
        Proto::Tcp4 | Proto::Tcp6 => format!(
            "{} -z -w 1 {} {} >/dev/null 2>&1 || true",
            nc_command, target_ip, target_port
        ),
        Proto::Udp4 | Proto::Udp6 => format!(
            "printf x | {} -u -w 1 {} {} >/dev/null 2>&1 || true",
            nc_command, target_ip, target_port
        ),
    };

    let result = shell::run_with_timeout(
        &shell_binary,
        &["-c", &command],
        Duration::from_millis(timeout_ms.max(250)),
    );

    let error = match result {
        Ok((rc, out)) => {
            if rc == 0 {
                String::new()
            } else {
                summarize_shell_text(out.trim())
            }
        }
        Err(err) => format!("{err:#}"),
    };

    (command, error)
}

fn run_active_self_test_for_capability(
    capability: &mut FirewallProtoCapability,
    timeout_ms: u64,
) -> crate::types::FirewallSelfTestResult {
    let target_ip = capability.proto.loopback_ip().to_string();
    let target_port = self_test_target_port(capability.proto);
    let mut result = crate::types::FirewallSelfTestResult {
        proto: capability.proto,
        chain_name: capability.chain_name.clone(),
        xtables_binary: capability.xtables_binary.clone(),
        target_ip: target_ip.clone(),
        target_port,
        command: String::new(),
        attempted: false,
        ok: false,
        packets_before: 0,
        packets_after: 0,
        bytes_before: 0,
        bytes_after: 0,
        error: String::new(),
    };

    if !capability.supported || capability.applied_rule_count == 0 || capability.chain_name.is_empty() {
        result.error = "skipped: no active supported rules".to_string();
        return result;
    }

    let test_uid = resolve_self_test_uid();
    let comment = format!("PG selftest {}", capability.proto.short_label());
    let args = build_self_test_rule_args(capability, test_uid, target_port, &comment);
    let (rc, out) = match xtablesv(&capability.xtables_binary, &args) {
        Ok(v) => v,
        Err(err) => {
            result.error = format!("self-test insert failed: {err:#}");
            capability.active_self_test_attempted = true;
            capability.active_self_test_error = result.error.clone();
            return result;
        }
    };
    if rc != 0 {
        result.error = format!("self-test insert failed: {}", summarize_shell_text(out.trim()));
        capability.active_self_test_attempted = true;
        capability.active_self_test_error = result.error.clone();
        return result;
    }

    result.attempted = true;
    capability.active_self_test_attempted = true;

    match read_chain_line_counter(capability.proto, &capability.chain_name, 1) {
        Ok((packets, bytes)) => {
            result.packets_before = packets;
            result.bytes_before = bytes;
        }
        Err(err) => {
            result.error = format!("self-test before-counter failed: {err:#}");
        }
    }

    let (command, command_error) = run_self_test_command(capability.proto, &target_ip, target_port, timeout_ms);
    result.command = command;

    match read_chain_line_counter(capability.proto, &capability.chain_name, 1) {
        Ok((packets, bytes)) => {
            result.packets_after = packets;
            result.bytes_after = bytes;
        }
        Err(err) => {
            if result.error.is_empty() {
                result.error = format!("self-test after-counter failed: {err:#}");
            }
        }
    }

    let _ = xtables(&capability.xtables_binary, &["-D", &capability.chain_name, "1"]);

    let packet_delta = result.packets_after.saturating_sub(result.packets_before);
    capability.active_self_test_packets = packet_delta;
    if packet_delta > 0 {
        result.ok = true;
        capability.active_self_test_ok = true;
        capability.active_self_test_error.clear();
    } else {
        if result.error.is_empty() {
            result.error = if command_error.is_empty() {
                "self-test counter did not increase".to_string()
            } else {
                format!("self-test counter did not increase; command={}", command_error)
            };
        }
        capability.active_self_test_ok = false;
        capability.active_self_test_error = result.error.clone();
    }

    result
}

pub fn run_active_self_tests(
    capabilities: &mut [FirewallProtoCapability],
    timeout_ms: u64,
) -> Vec<crate::types::FirewallSelfTestResult> {
    let mut out = Vec::new();
    for capability in capabilities {
        let result = run_active_self_test_for_capability(capability, timeout_ms);
        out.push(result);
    }
    out
}

pub fn read_counters(applied_rules: &[AppliedRule]) -> Result<Vec<RuleCounter>> {
    if applied_rules.is_empty() {
        return Ok(Vec::new());
    }

    let mut by_chain: BTreeMap<(Proto, String), Vec<&AppliedRule>> = BTreeMap::new();
    for rule in applied_rules {
        by_chain
            .entry((rule.proto, rule.chain_name.clone()))
            .or_default()
            .push(rule);
    }

    let mut out = Vec::new();
    for ((proto, chain_name), chain_rules) in by_chain {
        let binary = resolve_xtables_binary(proto);
        let (rc, text) = xtables(&binary, &["-L", &chain_name, "-n", "-v", "-x", "--line-numbers"])
            .with_context(|| format!("read counters for chain {}", chain_name))?;
        if rc != 0 {
            anyhow::bail!(
                "{} list counters failed for {}: {}",
                binary,
                chain_name,
                summarize_shell_text(text.trim())
            );
        }

        for line in text.lines() {
            let Some((line_no, packets, bytes)) = parse_rule_listing_counters(line) else {
                continue;
            };
            let Some(rule) = chain_rules.get(line_no.saturating_sub(1)) else {
                continue;
            };
            let tag = render_comment(rule);
            out.push(RuleCounter {
                chain_name: rule.chain_name.clone(),
                proto: rule.proto,
                blocked_uid: rule.blocked_uid,
                dst_ports: rule.dst_ports.clone(),
                owner_uids: rule.owner_uids.clone(),
                packets,
                bytes,
                tag,
            });
        }
    }

    Ok(out)
}
