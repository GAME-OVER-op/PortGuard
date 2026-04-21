use anyhow::{Context, Result};
use std::{
    env,
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant},
};

const DEFAULT_TIMEOUT_MS: u64 = 15_000;
const POLL_INTERVAL_MS: u64 = 50;
const MAX_ERROR_TEXT_CHARS: usize = 240;
const ANDROID_DEFAULT_PATH: &str = "/system/bin:/system/xbin:/vendor/bin:/system_ext/bin:/product/bin";

fn android_command_path() -> String {
    match env::var("PATH") {
        Ok(current) if !current.trim().is_empty() => {
            let mut parts = current
                .split(':')
                .filter(|v| !v.trim().is_empty())
                .map(|v| v.trim().to_string())
                .collect::<Vec<_>>();
            for extra in ANDROID_DEFAULT_PATH.split(':').rev() {
                if !parts.iter().any(|v| v == extra) {
                    parts.insert(0, extra.to_string());
                }
            }
            parts.join(":")
        }
        _ => ANDROID_DEFAULT_PATH.to_string(),
    }
}

fn sanitize_output(text: &str) -> String {
    let compact = text
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    if compact.is_empty() {
        return String::new();
    }
    let mut out = compact;
    if out.chars().count() > MAX_ERROR_TEXT_CHARS {
        out = out.chars().take(MAX_ERROR_TEXT_CHARS).collect::<String>() + "...";
    }
    out
}

fn spawn_command(cmd: &str, args: &[&str]) -> Result<std::process::Child> {
    Command::new(cmd)
        .args(args)
        .env("PATH", android_command_path())
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .with_context(|| format!("spawn {cmd} {:?}", args))
}

pub fn run(cmd: &str, args: &[&str]) -> Result<(i32, String)> {
    run_with_timeout(cmd, args, Duration::from_millis(DEFAULT_TIMEOUT_MS))
}

pub fn run_with_timeout(cmd: &str, args: &[&str], timeout: Duration) -> Result<(i32, String)> {
    let mut child = spawn_command(cmd, args)?;
    let started = Instant::now();

    loop {
        match child.try_wait().with_context(|| format!("wait {cmd} {:?}", args))? {
            Some(_) => {
                let output = child
                    .wait_with_output()
                    .with_context(|| format!("read output {cmd} {:?}", args))?;
                let rc = output.status.code().unwrap_or(-1);
                let mut text = String::new();
                text.push_str(&String::from_utf8_lossy(&output.stdout));
                text.push_str(&String::from_utf8_lossy(&output.stderr));
                return Ok((rc, text));
            }
            None => {
                if started.elapsed() >= timeout {
                    let _ = child.kill();
                    let output = child.wait_with_output().ok();
                    let mut text = String::new();
                    if let Some(output) = output {
                        text.push_str(&String::from_utf8_lossy(&output.stdout));
                        text.push_str(&String::from_utf8_lossy(&output.stderr));
                    }
                    let summary = sanitize_output(text.trim());
                    if summary.is_empty() {
                        anyhow::bail!(
                            "command timed out after {} ms: {} {:?}",
                            timeout.as_millis(),
                            cmd,
                            args,
                        );
                    }
                    anyhow::bail!(
                        "command timed out after {} ms: {} {:?}; output: {}",
                        timeout.as_millis(),
                        cmd,
                        args,
                        summary
                    );
                }
                thread::sleep(Duration::from_millis(POLL_INTERVAL_MS));
            }
        }
    }
}

pub fn runv<S: AsRef<str>>(cmd: &str, args: &[S]) -> Result<(i32, String)> {
    let owned: Vec<String> = args.iter().map(|s| s.as_ref().to_string()).collect();
    let refs: Vec<&str> = owned.iter().map(|s| s.as_str()).collect();
    run(cmd, &refs)
}
