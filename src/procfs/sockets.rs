use crate::types::SocketEntry;
use anyhow::{Context, Result};
use std::fs;

fn parse_ipv4_le(hex: &str) -> Result<String> {
    if hex.len() != 8 {
        anyhow::bail!("bad ipv4 hex length: {hex}");
    }
    let bytes = (0..4)
        .map(|i| u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16))
        .collect::<std::result::Result<Vec<_>, _>>()
        .with_context(|| format!("parse ipv4 bytes from {hex}"))?;
    Ok(format!("{}.{}.{}.{}", bytes[3], bytes[2], bytes[1], bytes[0]))
}

pub fn read_tcp4_listeners() -> Result<Vec<SocketEntry>> {
    let raw = fs::read_to_string("/proc/net/tcp").context("read /proc/net/tcp")?;
    let mut out = Vec::new();

    for (idx, line) in raw.lines().enumerate() {
        if idx == 0 || line.trim().is_empty() {
            continue;
        }
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() < 10 {
            continue;
        }

        let local = parts[1];
        let state = parts[3];
        let uid_str = parts[7];
        let inode_str = parts[9];

        if state != "0A" {
            continue;
        }

        let Some((ip_hex, port_hex)) = local.split_once(':') else {
            continue;
        };

        let local_ip = parse_ipv4_le(ip_hex)?;
        let port = u16::from_str_radix(port_hex, 16)
            .with_context(|| format!("parse port from {port_hex}"))?;
        let uid = uid_str.parse::<u32>().unwrap_or(0);
        let inode = inode_str.parse::<u64>().unwrap_or(0);

        out.push(SocketEntry {
            local_ip,
            port,
            uid,
            inode,
        });
    }

    out.sort_by_key(|v| (v.port, v.uid, v.inode));
    Ok(out)
}
