use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

pub const AID_USER_OFFSET: u32 = 100_000;
pub const AID_APP_START: u32 = 10_000;
pub const AID_APP_END: u32 = 19_999;
pub const AID_SDK_SANDBOX_START: u32 = 20_000;
pub const AID_SDK_SANDBOX_END: u32 = 29_999;
// Includes app-zygote isolated UIDs (90000..98999) and regular isolated UIDs
// (99000..99999) within every Android user namespace.
pub const AID_ISOLATED_START: u32 = 90_000;
pub const AID_ISOLATED_END: u32 = 99_999;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Proto {
    Tcp4,
    Udp4,
    Tcp6,
    Udp6,
}

impl Proto {
    pub const ALL: [Proto; 4] = [Proto::Tcp4, Proto::Udp4, Proto::Tcp6, Proto::Udp6];

    pub fn family_label(self) -> &'static str {
        match self {
            Self::Tcp4 | Self::Udp4 => "ipv4",
            Self::Tcp6 | Self::Udp6 => "ipv6",
        }
    }

    pub fn l4_label(self) -> &'static str {
        match self {
            Self::Tcp4 | Self::Tcp6 => "tcp",
            Self::Udp4 | Self::Udp6 => "udp",
        }
    }

    pub fn short_label(self) -> &'static str {
        match self {
            Self::Tcp4 => "tcp4",
            Self::Udp4 => "udp4",
            Self::Tcp6 => "tcp6",
            Self::Udp6 => "udp6",
        }
    }

    pub fn chain_suffix(self) -> &'static str {
        match self {
            Self::Tcp4 => "4T",
            Self::Udp4 => "4U",
            Self::Tcp6 => "6T",
            Self::Udp6 => "6U",
        }
    }

    pub fn xtables_binary(self) -> &'static str {
        match self {
            Self::Tcp4 | Self::Udp4 => "iptables",
            Self::Tcp6 | Self::Udp6 => "ip6tables",
        }
    }

    pub fn loopback_ip(self) -> &'static str {
        match self {
            Self::Tcp4 | Self::Udp4 => "127.0.0.1",
            Self::Tcp6 | Self::Udp6 => "::1",
        }
    }

    pub fn is_ipv6(self) -> bool {
        matches!(self, Self::Tcp6 | Self::Udp6)
    }

    pub fn is_enabled(self, cfg: &crate::config::Config) -> bool {
        match self {
            Self::Tcp4 => cfg.tcp4_enabled,
            Self::Udp4 => cfg.udp4_enabled,
            Self::Tcp6 => cfg.tcp6_enabled,
            Self::Udp6 => cfg.udp6_enabled,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Listener {
    pub proto: Proto,
    pub local_ip: String,
    pub port: u16,
    pub uid: u32,
    pub inode: u64,
    pub pid: Option<i32>,
    pub process_name: Option<String>,
    pub packages: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct DesiredRule {
    pub proto: Proto,
    pub blocked_uid: u32,
    pub dst_port: u16,
    pub owner_uid: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct AppliedRule {
    pub chain_name: String,
    pub proto: Proto,
    pub blocked_uid: u32,
    pub dst_ports: Vec<u16>,
    pub owner_uids: Vec<u32>,
    pub backend: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct FirewallProbeAttempt {
    pub backend: String,
    pub ok: bool,
    pub error: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct FirewallProtoCapability {
    pub proto: Proto,
    pub xtables_binary: String,
    pub chain_name: String,
    pub enabled_in_config: bool,
    pub rules_requested: usize,
    pub binary_available: bool,
    pub output_chain_readable: bool,
    pub chain_ops_ok: bool,
    pub owner_match_ok: bool,
    #[serde(default)]
    pub socket_exists_ok: bool,
    pub comment_match_ok: bool,
    pub multiport_ok: bool,
    pub supported: bool,
    pub selected_backend: String,
    pub applied_rule_count: usize,
    #[serde(default)]
    pub active_self_test_attempted: bool,
    #[serde(default)]
    pub active_self_test_ok: bool,
    #[serde(default)]
    pub active_self_test_error: String,
    #[serde(default)]
    pub active_self_test_packets: u64,
    pub last_error: String,
    pub attempts: Vec<FirewallProbeAttempt>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct FirewallSelfTestResult {
    pub proto: Proto,
    pub chain_name: String,
    pub xtables_binary: String,
    pub target_ip: String,
    pub target_port: u16,
    pub command: String,
    pub attempted: bool,
    pub ok: bool,
    pub packets_before: u64,
    pub packets_after: u64,
    pub bytes_before: u64,
    pub bytes_after: u64,
    pub error: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
pub struct LocalAddressSnapshot {
    pub proto: Proto,
    pub addresses: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
pub struct PackageIndex {
    /// A package may exist for multiple Android users/profiles and therefore have
    /// multiple concrete Linux UIDs at the same time.
    pub package_to_uids: BTreeMap<String, Vec<u32>>,
    pub uid_to_packages: BTreeMap<u32, Vec<String>>,
    #[serde(default)]
    pub third_party_uids: BTreeSet<u32>,
    #[serde(default)]
    pub transient_app_uids: BTreeSet<u32>,
}

impl PackageIndex {
    pub fn insert(&mut self, package: String, uid: u32) {
        let package_entry = self.package_to_uids.entry(package.clone()).or_default();
        if !package_entry.iter().any(|v| *v == uid) {
            package_entry.push(uid);
            package_entry.sort();
        }

        let uid_entry = self.uid_to_packages.entry(uid).or_default();
        if !uid_entry.iter().any(|v| v == &package) {
            uid_entry.push(package);
            uid_entry.sort();
        }
    }

    pub fn mark_third_party_uid(&mut self, uid: u32) {
        if Self::is_regular_app_uid(uid) {
            self.third_party_uids.insert(uid);
        }
    }

    pub fn set_transient_app_uids(&mut self, uids: BTreeSet<u32>) -> bool {
        let normalized = uids
            .into_iter()
            .filter(|uid| Self::is_sdk_sandbox_uid(*uid) || Self::is_isolated_uid(*uid))
            .collect::<BTreeSet<_>>();
        if self.transient_app_uids == normalized {
            return false;
        }
        self.transient_app_uids = normalized;
        true
    }

    pub fn package_count(&self) -> usize {
        self.package_to_uids.len()
    }

    pub fn uid_user_id(uid: u32) -> u32 {
        uid / AID_USER_OFFSET
    }

    pub fn uid_app_id(uid: u32) -> u32 {
        uid % AID_USER_OFFSET
    }

    pub fn is_regular_app_uid(uid: u32) -> bool {
        let app_id = Self::uid_app_id(uid);
        (AID_APP_START..=AID_APP_END).contains(&app_id)
    }

    pub fn is_sdk_sandbox_uid(uid: u32) -> bool {
        let app_id = Self::uid_app_id(uid);
        (AID_SDK_SANDBOX_START..=AID_SDK_SANDBOX_END).contains(&app_id)
    }

    pub fn is_isolated_uid(uid: u32) -> bool {
        let app_id = Self::uid_app_id(uid);
        (AID_ISOLATED_START..=AID_ISOLATED_END).contains(&app_id)
    }

    pub fn app_uids(&self) -> BTreeSet<u32> {
        let mut out = if self.third_party_uids.is_empty() {
            self.uid_to_packages
                .keys()
                .copied()
                .filter(|uid| Self::is_regular_app_uid(*uid))
                .collect::<BTreeSet<_>>()
        } else {
            self.third_party_uids.clone()
        };
        out.extend(self.transient_app_uids.iter().copied());
        out
    }

    pub fn all_known_uids(&self) -> BTreeSet<u32> {
        let mut out = self.uid_to_packages.keys().copied().collect::<BTreeSet<_>>();
        out.extend(self.transient_app_uids.iter().copied());
        out
    }

    pub fn is_known_regular_app_uid(&self, uid: u32) -> bool {
        Self::is_regular_app_uid(uid) && self.uid_to_packages.contains_key(&uid)
    }

    pub fn resolve_uids_for_packages<'a, I>(&self, packages: I) -> BTreeSet<u32>
    where
        I: IntoIterator<Item = &'a String>,
    {
        let mut out = BTreeSet::new();
        for package in packages {
            if let Some(uids) = self.package_to_uids.get(package) {
                out.extend(uids.iter().copied());
            }
        }
        out
    }

    pub fn packages_for_uid(&self, uid: u32) -> Vec<String> {
        self.uid_to_packages.get(&uid).cloned().unwrap_or_default()
    }
}

#[derive(Debug, Clone)]
pub struct SocketEntry {
    pub proto: Proto,
    pub local_ip: String,
    pub port: u16,
    pub uid: u32,
    pub inode: u64,
}

#[derive(Debug, Clone)]
pub struct ProcessInfo {
    pub pid: i32,
    pub process_name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RuleCounter {
    pub chain_name: String,
    pub proto: Proto,
    pub blocked_uid: u32,
    pub dst_ports: Vec<u16>,
    pub owner_uids: Vec<u32>,
    pub packets: u64,
    pub bytes: u64,
    pub tag: String,
}
