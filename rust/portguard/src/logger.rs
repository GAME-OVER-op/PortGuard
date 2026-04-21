use std::time::{SystemTime, UNIX_EPOCH};

fn now_string() -> String {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    secs.to_string()
}

fn log(level: &str, scope: &str, message: &str) {
    println!("[{}] [{}] [{}] {}", now_string(), level, scope, message);
}

pub fn info(scope: &str, message: &str) {
    log("INFO", scope, message);
}

pub fn warn(scope: &str, message: &str) {
    log("WARN", scope, message);
}

pub fn error(scope: &str, message: &str) {
    log("ERROR", scope, message);
}
