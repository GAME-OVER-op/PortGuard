use crate::{config::Config, logger, runtime::state::DetectorState, shell};

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
    if uid < 10_000 || cfg.uid_is_trusted(uid) {
        return;
    }

    let now = now_secs();
    for package in packages {
        if cfg.package_is_kill_exempt(package) || cfg.package_is_scan_ignored(package) {
            continue;
        }

        let last = state.last_action_epoch.get(package).copied().unwrap_or(0);
        if now.saturating_sub(last) < cfg.reaction_cooldown_secs {
            continue;
        }

        match shell::run("am", &["force-stop", package.as_str()]) {
            Ok((rc, _text)) if rc == 0 => {
                logger::warn(
                    "reaction",
                    &format!(
                        "force-stopped package={} uid={} reason={}",
                        package, uid, reason
                    ),
                );
                state.last_action_epoch.insert(package.clone(), now);
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
