use crate::{
    shell,
    types::{AppliedRule, DesiredRule, RuleCounter},
};
use anyhow::{Context, Result};
use std::collections::{BTreeMap, BTreeSet};

const IPTABLES_WAIT_SECS: &str = "5";
const MULTIPORT_MAX_PORTS: usize = 15;
const MAX_SHELL_ERROR_CHARS: usize = 240;

#[derive(Debug, Clone, Copy)]
enum LocalMatchMode {
    DstTypeLocal,
    LoopbackIface,
    Dest127,
    LoAndDest127,
}

impl LocalMatchMode {
    fn describe(self) -> &'static str {
        match self {
            Self::DstTypeLocal => "dst-type LOCAL",
            Self::LoopbackIface => "-o lo",
            Self::Dest127 => "-d 127.0.0.1",
            Self::LoAndDest127 => "-o lo -d 127.0.0.1",
        }
    }

    fn push_rule_args(self, args: &mut Vec<String>) {
        match self {
            Self::DstTypeLocal => {
                args.push("-m".to_string());
                args.push("addrtype".to_string());
                args.push("--dst-type".to_string());
                args.push("LOCAL".to_string());
            }
            Self::LoopbackIface => {
                args.push("-o".to_string());
                args.push("lo".to_string());
            }
            Self::Dest127 => {
                args.push("-d".to_string());
                args.push("127.0.0.1".to_string());
            }
            Self::LoAndDest127 => {
                args.push("-o".to_string());
                args.push("lo".to_string());
                args.push("-d".to_string());
                args.push("127.0.0.1".to_string());
            }
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

#[derive(Debug, Clone, Copy)]
struct BackendMode {
    local: LocalMatchMode,
    ports: PortMatchMode,
}

impl BackendMode {
    fn describe(self) -> String {
        format!("local={}, ports={}", self.local.describe(), self.ports.describe())
    }
}

#[derive(Debug, Clone)]
pub struct ApplyReport {
    pub actual_rule_count: usize,
    pub backend: String,
    pub applied_rules: Vec<AppliedRule>,
}

fn summarize_shell_text(text: &str) -> String {
    let compact = text
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    if compact.is_empty() {
        return String::new();
    }
    if compact.chars().count() > MAX_SHELL_ERROR_CHARS {
        compact.chars().take(MAX_SHELL_ERROR_CHARS).collect::<String>() + "..."
    } else {
        compact
    }
}

fn candidate_modes() -> [BackendMode; 8] {
    [
        BackendMode { local: LocalMatchMode::DstTypeLocal, ports: PortMatchMode::Multiport },
        BackendMode { local: LocalMatchMode::LoopbackIface, ports: PortMatchMode::Multiport },
        BackendMode { local: LocalMatchMode::Dest127, ports: PortMatchMode::Multiport },
        BackendMode { local: LocalMatchMode::LoAndDest127, ports: PortMatchMode::Multiport },
        BackendMode { local: LocalMatchMode::DstTypeLocal, ports: PortMatchMode::PerPort },
        BackendMode { local: LocalMatchMode::LoopbackIface, ports: PortMatchMode::PerPort },
        BackendMode { local: LocalMatchMode::Dest127, ports: PortMatchMode::PerPort },
        BackendMode { local: LocalMatchMode::LoAndDest127, ports: PortMatchMode::PerPort },
    ]
}

fn iptables(args: &[&str]) -> Result<(i32, String)> {
    let mut full = vec!["-w", IPTABLES_WAIT_SECS];
    full.extend_from_slice(args);
    shell::run("iptables", &full)
}

fn iptablesv(args: &[String]) -> Result<(i32, String)> {
    let mut full = vec!["-w".to_string(), IPTABLES_WAIT_SECS.to_string()];
    full.extend(args.iter().cloned());
    shell::runv("iptables", &full)
}

fn hook_args(chain: &str) -> [&str; 5] {
    ["OUTPUT", "-p", "tcp", "-j", chain]
}

fn ensure_chain_exists(chain: &str) -> Result<()> {
    let (rc, _) = iptables(&["-L", chain])?;
    if rc != 0 {
        let (rc, out) = iptables(&["-N", chain])?;
        if rc != 0 {
            anyhow::bail!("iptables -N {} failed: {}", chain, summarize_shell_text(&out));
        }
    }
    Ok(())
}

fn remove_hook_if_present(chain: &str) -> Result<()> {
    loop {
        let args = hook_args(chain);
        let mut full = vec!["-D"];
        full.extend_from_slice(&args);
        let (rc, _) = iptables(&full)?;
        if rc != 0 {
            break;
        }
    }
    Ok(())
}

fn ensure_hook(chain: &str) -> Result<()> {
    let args = hook_args(chain);
    let mut check = vec!["-C"];
    check.extend_from_slice(&args);
    let (rc, _) = iptables(&check)?;
    if rc == 0 {
        return Ok(());
    }

    let mut install = vec!["-I"];
    install.extend_from_slice(&args);
    let (rc, out) = iptables(&install)?;
    if rc != 0 {
        anyhow::bail!("iptables hook install failed: {}", summarize_shell_text(&out));
    }
    Ok(())
}

pub fn clear_chain(chain: &str) -> Result<()> {
    remove_hook_if_present(chain)?;
    let _ = iptables(&["-F", chain]);
    let _ = iptables(&["-X", chain]);
    Ok(())
}

fn render_comment(rule: &AppliedRule) -> String {
    let ports = rule
        .dst_ports
        .iter()
        .map(|v| v.to_string())
        .collect::<Vec<_>>()
        .join(",");
    let owners = rule
        .owner_uids
        .iter()
        .map(|v| v.to_string())
        .collect::<Vec<_>>()
        .join(",");
    format!("PG uid={} ports={} owners={}", rule.blocked_uid, ports, owners)
}

fn add_rule(chain: &str, rule: &AppliedRule, mode: BackendMode) -> Result<()> {
    let comment = render_comment(rule);
    let mut args = vec![
        "-A".to_string(),
        chain.to_string(),
        "-p".to_string(),
        "tcp".to_string(),
    ];

    mode.local.push_rule_args(&mut args);

    args.extend([
        "-m".to_string(),
        "owner".to_string(),
        "--uid-owner".to_string(),
        rule.blocked_uid.to_string(),
    ]);

    match mode.ports {
        PortMatchMode::Multiport => {
            args.extend([
                "-m".to_string(),
                "multiport".to_string(),
                "--dports".to_string(),
                rule.dst_ports
                    .iter()
                    .map(|v| v.to_string())
                    .collect::<Vec<_>>()
                    .join(","),
            ]);
        }
        PortMatchMode::PerPort => {
            let port = rule.dst_ports.first().copied().unwrap_or_default();
            args.extend(["--dport".to_string(), port.to_string()]);
        }
    }

    args.extend([
        "-m".to_string(),
        "comment".to_string(),
        "--comment".to_string(),
        comment,
        "-j".to_string(),
        "DROP".to_string(),
    ]);

    let (rc, out) = iptablesv(&args)?;
    if rc != 0 {
        anyhow::bail!("iptables add rule failed ({}): {}", mode.describe(), summarize_shell_text(out.trim()));
    }
    Ok(())
}

fn chunk_ports(sorted_ports: &[u16], size: usize) -> Vec<Vec<u16>> {
    if size == 0 {
        return Vec::new();
    }
    sorted_ports.chunks(size).map(|c| c.to_vec()).collect()
}

fn group_rules(rules: &[DesiredRule], mode: PortMatchMode) -> Vec<AppliedRule> {
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
                blocked_uid,
                dst_ports: chunk,
                owner_uids: owner_uids.into_iter().collect(),
            });
        }
    }

    out
}

fn apply_rules_with_mode(chain: &str, desired_rules: &[DesiredRule], mode: BackendMode) -> Result<ApplyReport> {
    let grouped = group_rules(desired_rules, mode.ports);
    ensure_chain_exists(chain)?;
    let (rc, out) = iptables(&["-F", chain])?;
    if rc != 0 {
        anyhow::bail!("iptables -F {} failed: {}", chain, summarize_shell_text(out.trim()));
    }

    for rule in &grouped {
        add_rule(chain, rule, mode)?;
    }

    ensure_hook(chain)?;

    Ok(ApplyReport {
        actual_rule_count: grouped.len(),
        backend: mode.describe(),
        applied_rules: grouped,
    })
}

pub fn apply_rules(chain: &str, desired_rules: &[DesiredRule]) -> Result<ApplyReport> {
    let mut errors = Vec::new();

    for mode in candidate_modes() {
        match apply_rules_with_mode(chain, desired_rules, mode) {
            Ok(report) => return Ok(report),
            Err(err) => {
                errors.push(format!("{} => {err:#}", mode.describe()));
                let _ = iptables(&["-F", chain]);
            }
        }
    }

    anyhow::bail!("all iptables backends failed: {}", errors.join(" | "))
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

pub fn read_counters(chain: &str, applied_rules: &[AppliedRule]) -> Result<Vec<RuleCounter>> {
    if applied_rules.is_empty() {
        return Ok(Vec::new());
    }

    let (rc, text) = iptables(&["-L", chain, "-n", "-v", "-x", "--line-numbers"])
        .with_context(|| format!("read counters for chain {chain}"))?;
    if rc != 0 {
        anyhow::bail!(
            "iptables list counters failed for {}: {}",
            chain,
            summarize_shell_text(text.trim())
        );
    }

    let mut out = Vec::new();
    for line in text.lines() {
        let Some((line_no, packets, bytes)) = parse_rule_listing_counters(line) else {
            continue;
        };
        let Some(rule) = applied_rules.get(line_no.saturating_sub(1)) else {
            continue;
        };
        let tag = render_comment(rule);
        out.push(RuleCounter {
            blocked_uid: rule.blocked_uid,
            dst_ports: rule.dst_ports.clone(),
            owner_uids: rule.owner_uids.clone(),
            packets,
            bytes,
            tag,
        });
    }

    Ok(out)
}
