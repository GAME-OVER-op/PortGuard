use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
pub enum Proto {
    Tcp4,
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

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
pub struct DesiredRule {
    pub blocked_uid: u32,
    pub dst_port: u16,
    pub owner_uid: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct AppliedRule {
    pub blocked_uid: u32,
    pub dst_ports: Vec<u16>,
    pub owner_uids: Vec<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
pub struct PackageIndex {
    pub package_to_uid: BTreeMap<String, u32>,
    pub uid_to_packages: BTreeMap<u32, Vec<String>>,
}

impl PackageIndex {
    pub fn insert(&mut self, package: String, uid: u32) {
        self.package_to_uid.insert(package.clone(), uid);
        let entry = self.uid_to_packages.entry(uid).or_default();
        if !entry.iter().any(|v| v == &package) {
            entry.push(package);
            entry.sort();
        }
    }

    pub fn app_uids(&self) -> BTreeSet<u32> {
        self.uid_to_packages
            .keys()
            .copied()
            .filter(|uid| *uid >= 10_000)
            .collect()
    }

    pub fn resolve_uids_for_packages<'a, I>(&self, packages: I) -> BTreeSet<u32>
    where
        I: IntoIterator<Item = &'a String>,
    {
        let mut out = BTreeSet::new();
        for package in packages {
            if let Some(uid) = self.package_to_uid.get(package) {
                out.insert(*uid);
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
    pub blocked_uid: u32,
    pub dst_ports: Vec<u16>,
    pub owner_uids: Vec<u32>,
    pub packets: u64,
    pub bytes: u64,
    pub tag: String,
}
