use crate::types::{Proto, SocketEntry};
use anyhow::{Context, Result};
use std::{fs, net::Ipv6Addr};

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

fn parse_ipv6_proc_addr(hex: &str) -> Result<String> {
    if hex.len() != 32 {
        anyhow::bail!("bad ipv6 hex length: {hex}");
    }

    let mut out = [0_u8; 16];
    for (chunk_idx, chunk) in hex.as_bytes().chunks(8).enumerate() {
        let chunk = std::str::from_utf8(chunk)?;
        let bytes = (0..4)
            .map(|i| u8::from_str_radix(&chunk[i * 2..i * 2 + 2], 16))
            .collect::<std::result::Result<Vec<_>, _>>()
            .with_context(|| format!("parse ipv6 bytes from {chunk}"))?;
        let start = chunk_idx * 4;
        out[start] = bytes[3];
        out[start + 1] = bytes[2];
        out[start + 2] = bytes[1];
        out[start + 3] = bytes[0];
    }

    Ok(Ipv6Addr::from(out).to_string())
}

fn parse_local_ip(proto: Proto, ip_hex: &str) -> Result<String> {
    match proto {
        Proto::Tcp4 | Proto::Udp4 => parse_ipv4_le(ip_hex),
        Proto::Tcp6 | Proto::Udp6 => parse_ipv6_proc_addr(ip_hex),
    }
}

fn remote_is_unconnected(proto: Proto, remote: &str) -> bool {
    let Some((ip_hex, port_hex)) = remote.split_once(':') else {
        return false;
    };

    let ip_zero = match proto {
        Proto::Tcp4 | Proto::Udp4 => ip_hex.eq_ignore_ascii_case("00000000"),
        Proto::Tcp6 | Proto::Udp6 => ip_hex.eq_ignore_ascii_case("00000000000000000000000000000000"),
    };

    ip_zero && port_hex.eq_ignore_ascii_case("0000")
}

fn include_entry(proto: Proto, state: &str, port: u16, remote: &str) -> bool {
    if port == 0 {
        return false;
    }
    match proto {
        Proto::Tcp4 | Proto::Tcp6 => state == "0A",
        Proto::Udp4 | Proto::Udp6 => remote_is_unconnected(proto, remote),
    }
}

fn read_socket_table(path: &str, proto: Proto) -> Result<Vec<SocketEntry>> {
    let raw = fs::read_to_string(path).with_context(|| format!("read {path}"))?;
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
        let remote = parts[2];
        let state = parts[3];
        let uid_str = parts[7];
        let inode_str = parts[9];

        let Some((ip_hex, port_hex)) = local.split_once(':') else {
            continue;
        };

        let port = u16::from_str_radix(port_hex, 16)
            .with_context(|| format!("parse port from {port_hex}"))?;
        if !include_entry(proto, state, port, remote) {
            continue;
        }

        let local_ip = parse_local_ip(proto, ip_hex)?;
        let uid = uid_str.parse::<u32>().unwrap_or(0);
        let inode = inode_str.parse::<u64>().unwrap_or(0);

        out.push(SocketEntry {
            proto,
            local_ip,
            port,
            uid,
            inode,
        });
    }

    out.sort_by_key(|v| (v.proto, v.port, v.uid, v.inode));
    out.dedup_by(|a, b| {
        a.proto == b.proto
            && a.local_ip == b.local_ip
            && a.port == b.port
            && a.uid == b.uid
            && a.inode == b.inode
    });
    Ok(out)
}

pub fn read_tcp4_listeners() -> Result<Vec<SocketEntry>> {
    read_socket_table("/proc/net/tcp", Proto::Tcp4)
}

pub fn read_udp4_sockets() -> Result<Vec<SocketEntry>> {
    read_socket_table("/proc/net/udp", Proto::Udp4)
}

pub fn read_tcp6_listeners() -> Result<Vec<SocketEntry>> {
    read_socket_table("/proc/net/tcp6", Proto::Tcp6)
}

pub fn read_udp6_sockets() -> Result<Vec<SocketEntry>> {
    read_socket_table("/proc/net/udp6", Proto::Udp6)
}
