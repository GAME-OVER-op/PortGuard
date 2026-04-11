use crate::{config::Config, logger, shell, types::PackageIndex};
use anyhow::{Context, Result};
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

fn parse_cmd_package_list(raw: &str, out: &mut PackageIndex) {
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
        }
    }
}

fn parse_cmd_package_names(raw: &str) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    for line in raw.lines() {
        let line = line.trim();
        if !line.starts_with("package:") {
            continue;
        }
        for part in line.split_whitespace() {
            if let Some(value) = part.strip_prefix("package:") {
                if !value.is_empty() {
                    out.insert(value.to_string());
                }
            }
        }
    }
    out
}

fn run_package_list(candidates: &[(&str, &[&str])]) -> Result<Option<(String, String)>> {
    for (cmd, args) in candidates {
        match shell::run(cmd, args) {
            Ok((0, text)) => return Ok(Some(((*cmd).to_string(), text))),
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
    Ok(None)
}

fn load_third_party_package_names() -> Result<BTreeSet<String>> {
    let candidates: [(&str, &[&str]); 2] = [
        ("cmd", &["package", "list", "packages", "-U", "-3"]),
        ("pm", &["list", "packages", "-U", "-3"]),
    ];

    if let Some((_cmd, text)) = run_package_list(&candidates)? {
        let out = parse_cmd_package_names(&text);
        if !out.is_empty() {
            return Ok(out);
        }
    }

    Ok(BTreeSet::new())
}

pub fn load_package_index(cfg: &Config) -> Result<PackageIndex> {
    let mut out = PackageIndex::default();

    if cfg.auto_discover_packages {
        for source in &cfg.package_uid_sources {
            match fs::read_to_string(source) {
                Ok(raw) => parse_packages_list(&raw, &mut out),
                Err(err) => logger::warn(
                    "packages",
                    &format!("cannot read {}: {}", source, err),
                ),
            }
        }
    }

    if out.package_to_uid.is_empty() {
        let candidates: [(&str, &[&str]); 2] = [
            ("cmd", &["package", "list", "packages", "-U"]),
            ("pm", &["list", "packages", "-U"]),
        ];
        if let Some((_cmd, text)) = run_package_list(&candidates)? {
            parse_cmd_package_list(&text, &mut out);
        } else {
            anyhow::bail!("unable to discover installed packages from configured sources or package manager");
        }
    }

    if cfg.user_apps_only {
        let third_party = load_third_party_package_names().context("load third-party package names")?;
        if third_party.is_empty() {
            logger::warn(
                "packages",
                "third-party package filter is empty; keeping regular app UID filtering only",
            );
        } else {
            let before_packages = out.package_to_uid.len();
            let before_uids = out.uid_to_packages.len();
            out.retain_only_packages(&third_party);
            logger::info(
                "packages",
                &format!(
                    "third-party package filter applied: packages {} -> {}, uids {} -> {}",
                    before_packages,
                    out.package_to_uid.len(),
                    before_uids,
                    out.uid_to_packages.len()
                ),
            );
        }
    }

    Ok(out)
}
