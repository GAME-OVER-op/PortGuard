use crate::{
    config::Config,
    logger,
    runtime::state::{append_incident, DetectorState, IncidentRecord},
    shell,
    types::PackageIndex,
};

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

pub fn maybe_force_stop(cfg: &Config, state: &mut DetectorState, uid: u32, packages: &[String], reason: &str) {
    if cfg.learning_mode || !cfg.reaction_force_stop_enabled() {
        return;
    }
    // Linux UID must be interpreted by Android appId, not by its absolute
    // numeric value. Secondary users/work profiles add user_id * 100000, so a
    // system UID there is numerically >10000 but is still not an app UID.
    if !PackageIndex::is_regular_app_uid(uid) || cfg.uid_is_trusted(uid) {
        return;
    }

    let now = now_secs();
    let android_user = PackageIndex::uid_user_id(uid).to_string();
    for package in packages {
        if cfg.package_is_kill_exempt(package) || cfg.package_is_scan_ignored(package) {
            continue;
        }

        let action_key = format!("{}:{}", android_user, package);
        let last = state.last_action_epoch.get(&action_key).copied().unwrap_or(0);
        if now.saturating_sub(last) < cfg.reaction_cooldown_secs {
            continue;
        }

        match shell::run(
            "am",
            &["force-stop", "--user", android_user.as_str(), package.as_str()],
        ) {
            Ok((rc, _text)) if rc == 0 => {
                logger::warn(
                    "reaction",
                    &format!(
                        "force-stopped package={} uid={} reason={}",
                        package, uid, reason
                    ),
                );
                state.last_action_epoch.insert(action_key, now);
                if let Err(err) = append_incident(
                    &cfg.state_dir,
                    &IncidentRecord {
                        timestamp: now,
                        level: "WARN".to_string(),
                        uid,
                        packages: packages.to_vec(),
                        ports: Vec::new(),
                        action: format!("force_stop:{}", package),
                        message: reason.to_string(),
                    },
                ) {
                    logger::warn("reaction", &format!("incident write failed: {err:#}"));
                }
            }
            Ok((rc, text)) => {
                logger::warn(
                    "reaction",
                    &format!(
                        "force-stop failed package={} uid={} rc={} output={}",
                        package,
                        uid,
                        rc,
                        text.split_whitespace().collect::<Vec<_>>().join(" ")
                    ),
                );
            }
            Err(err) => {
                logger::warn(
                    "reaction",
                    &format!(
                        "force-stop error package={} uid={} error={:#}",
                        package, uid, err
                    ),
                );
            }
        }
    }
}
