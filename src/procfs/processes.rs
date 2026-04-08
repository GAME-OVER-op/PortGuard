use crate::types::ProcessInfo;
use anyhow::Result;
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::Path,
};

fn read_cmdline(pid: i32) -> String {
    let path = format!("/proc/{pid}/cmdline");
    match fs::read(&path) {
        Ok(bytes) if !bytes.is_empty() => {
            let parts: Vec<String> = bytes
                .split(|b| *b == 0)
                .filter(|s| !s.is_empty())
                .map(|s| String::from_utf8_lossy(s).to_string())
                .collect();
            if parts.is_empty() {
                pid.to_string()
            } else {
                parts.join(" ")
            }
        }
        _ => pid.to_string(),
    }
}

pub fn find_processes_by_inodes(inodes: &BTreeSet<u64>) -> Result<BTreeMap<u64, ProcessInfo>> {
    let mut out = BTreeMap::new();
    if inodes.is_empty() {
        return Ok(out);
    }

    for entry in fs::read_dir("/proc")? {
        let entry = match entry {
            Ok(v) => v,
            Err(_) => continue,
        };
        let file_name = entry.file_name();
        let Some(pid) = file_name.to_str().and_then(|s| s.parse::<i32>().ok()) else {
            continue;
        };
        let fd_dir = entry.path().join("fd");
        if !fd_dir.is_dir() {
            continue;
        }

        let rd = match fs::read_dir(&fd_dir) {
            Ok(v) => v,
            Err(_) => continue,
        };

        for fd_entry in rd {
            let fd_entry = match fd_entry {
                Ok(v) => v,
                Err(_) => continue,
            };
            let target = match fs::read_link(fd_entry.path()) {
                Ok(v) => v,
                Err(_) => continue,
            };

            let target_str = target.to_string_lossy();
            if !target_str.starts_with("socket:[") || !target_str.ends_with(']') {
                continue;
            }
            let inode_text = &target_str["socket:[".len()..target_str.len() - 1];
            let inode = match inode_text.parse::<u64>() {
                Ok(v) => v,
                Err(_) => continue,
            };
            if !inodes.contains(&inode) || out.contains_key(&inode) {
                continue;
            }
            out.insert(
                inode,
                ProcessInfo {
                    pid,
                    process_name: read_cmdline(pid),
                },
            );
        }
    }

    Ok(out)
}
