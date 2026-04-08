use crate::{config::Config, logger, shell, types::PackageIndex};
use anyhow::{Context, Result};
use std::fs;

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
        let (rc, text) = shell::run("cmd", &["package", "list", "packages", "-U"])
            .context("run cmd package list packages -U")?;
        if rc == 0 {
            parse_cmd_package_list(&text, &mut out);
        } else {
            logger::warn("packages", &format!("cmd fallback failed rc={rc}: {text}"));
        }
    }

    Ok(out)
}
