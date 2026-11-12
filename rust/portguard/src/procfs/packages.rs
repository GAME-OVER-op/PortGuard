use crate::{config::Config, logger, shell, types::PackageIndex};
use anyhow::Result;
use std::{collections::BTreeSet, fs};

fn parse_packages_list(raw: &str, out: &mut PackageIndex) {
    for line in raw.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() < 2 {
            continue;
        }
        let package = parts[0].trim();
        let uid = match parts[1].parse::<u32>() {
            Ok(v) => v,
            Err(_) => continue,
        };
        if !package.is_empty() {
            out.insert(package.to_string(), uid);
        }
    }
}

fn parse_cmd_package_list(raw: &str, out: &mut PackageIndex, mark_third_party: bool) {
    for line in raw.lines() {
        let line = line.trim();
        if !line.starts_with("package:") {
            continue;
        }

        let mut package_name: Option<String> = None;
        let mut uid: Option<u32> = None;

        for part in line.split_whitespace() {
            if let Some(value) = part.strip_prefix("package:") {
                if !value.is_empty() {
                    package_name = Some(value.to_string());
                }
            } else if let Some(value) = part.strip_prefix("uid:") {
                if let Ok(v) = value.parse::<u32>() {
                    uid = Some(v);
                }
            }
        }

        if let (Some(package), Some(uid)) = (package_name, uid) {
            out.insert(package, uid);
            if mark_third_party {
                out.mark_third_party_uid(uid);
            }
        }
    }
}

fn parse_user_ids(raw: &str) -> BTreeSet<u32> {
    let mut out = BTreeSet::new();
    for line in raw.lines() {
        let Some(start) = line.find("UserInfo{") else {
            continue;
        };
        let tail = &line[start + "UserInfo{".len()..];
        let id_text = tail.split(':').next().unwrap_or("").trim();
        if let Ok(id) = id_text.parse::<u32>() {
            out.insert(id);
        }
    }
    out
}

fn discover_user_ids() -> BTreeSet<u32> {
    let candidates: [(&str, &[&str]); 2] = [
        ("cmd", &["user", "list"]),
        ("pm", &["list", "users"]),
    ];

    for (cmd, args) in candidates {
        match shell::run(cmd, args) {
            Ok((0, text)) => {
                let mut ids = parse_user_ids(&text);
                ids.insert(0);
                return ids;
            }
            Ok((rc, text)) => logger::warn(
                "packages",
                &format!("{} {:?} failed rc={}: {}", cmd, args, rc, text.trim()),
            ),
            Err(err) => logger::warn(
                "packages",
                &format!("{} {:?} failed: {}", cmd, args, err),
            ),
        }
    }

    [0_u32].into_iter().collect()
}

fn run_package_list_for_user(user_id: u32, third_party_only: bool) -> Option<String> {
    let user = user_id.to_string();
    let mut cmd_args = vec![
        "package".to_string(),
        "list".to_string(),
        "packages".to_string(),
        "-U".to_string(),
    ];
    if third_party_only {
        cmd_args.push("-3".to_string());
    }
    cmd_args.push("--user".to_string());
    cmd_args.push(user.clone());

    match shell::runv("cmd", &cmd_args) {
        Ok((0, text)) => return Some(text),
        Ok((rc, text)) => logger::warn(
            "packages",
            &format!("cmd {:?} failed rc={}: {}", cmd_args, rc, text.trim()),
        ),
        Err(err) => logger::warn(
            "packages",
            &format!("cmd {:?} failed: {}", cmd_args, err),
        ),
    }

    let mut pm_args = vec!["list".to_string(), "packages".to_string(), "-U".to_string()];
    if third_party_only {
        pm_args.push("-3".to_string());
    }
    pm_args.push("--user".to_string());
    pm_args.push(user);

    match shell::runv("pm", &pm_args) {
        Ok((0, text)) => Some(text),
        Ok((rc, text)) => {
            logger::warn(
                "packages",
                &format!("pm {:?} failed rc={}: {}", pm_args, rc, text.trim()),
            );
            None
        }
        Err(err) => {
            logger::warn("packages", &format!("pm {:?} failed: {}", pm_args, err));
            None
        }
    }
}

pub(crate) fn load_transient_app_uids() -> BTreeSet<u32> {
    let mut out = BTreeSet::new();
    let Ok(entries) = fs::read_dir("/proc") else {
        return out;
    };

    for entry in entries.flatten() {
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if name.parse::<u32>().is_err() {
            continue;
        }

        let status_path = entry.path().join("status");
        let Ok(raw) = fs::read_to_string(status_path) else {
            continue;
        };
        let Some(uid_line) = raw.lines().find(|line| line.starts_with("Uid:")) else {
            continue;
        };
        for value in uid_line.split_whitespace().skip(1) {
            let Ok(uid) = value.parse::<u32>() else {
                continue;
            };
            if PackageIndex::is_sdk_sandbox_uid(uid) || PackageIndex::is_isolated_uid(uid) {
                out.insert(uid);
            }
        }
    }

    out
}

pub fn load_package_index(cfg: &Config) -> Result<PackageIndex> {
    let mut out = PackageIndex::default();

    if cfg.auto_discover_packages {
        for source in &cfg.package_uid_sources {
            match fs::read_to_string(source) {
                Ok(raw) => parse_packages_list(&raw, &mut out),
                Err(err) => logger::warn("packages", &format!("cannot read {}: {}", source, err)),
            }
        }
    }

    // packages.list is fast but does not fully describe secondary users/work
    // profiles. Augment it from PackageManager per Android user and, at the same
    // time, record the real UIDs of third-party packages without deleting system
    // packages from the index (owner/package diagnostics still need them).
    let user_ids = discover_user_ids();
    for user_id in &user_ids {
        if let Some(text) = run_package_list_for_user(*user_id, false) {
            parse_cmd_package_list(&text, &mut out, false);
        }
        if cfg.user_apps_only {
            if let Some(text) = run_package_list_for_user(*user_id, true) {
                parse_cmd_package_list(&text, &mut out, true);
            }
        }
    }

    if out.package_count() == 0 {
        anyhow::bail!("unable to discover installed packages from configured sources or package manager");
    }

    out.set_transient_app_uids(load_transient_app_uids());

    if cfg.user_apps_only && out.third_party_uids.is_empty() {
        logger::warn(
            "packages",
            "third-party UID discovery is empty; falling back to regular app UID filtering",
        );
    }

    logger::info(
        "packages",
        &format!(
            "package index loaded: users={} packages={} uids={} third_party_uids={} transient_uids={}",
            user_ids.len(),
            out.package_count(),
            out.uid_to_packages.len(),
            out.third_party_uids.len(),
            out.transient_app_uids.len(),
        ),
    );

    Ok(out)
}
