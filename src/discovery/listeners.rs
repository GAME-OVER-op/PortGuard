use crate::{
    config::Config,
    procfs::{processes, sockets},
    types::{Listener, PackageIndex},
};
use anyhow::Result;
use std::collections::BTreeSet;

pub fn discover(cfg: &Config, packages: &PackageIndex) -> Result<Vec<Listener>> {
    let mut out = Vec::new();
    let mut sock_entries = Vec::new();

    if cfg.tcp4_enabled {
        sock_entries.extend(sockets::read_tcp4_listeners()?);
    }
    if cfg.udp4_enabled {
        sock_entries.extend(sockets::read_udp4_sockets()?);
    }
    if cfg.tcp6_enabled {
        sock_entries.extend(sockets::read_tcp6_listeners()?);
    }
    if cfg.udp6_enabled {
        sock_entries.extend(sockets::read_udp6_sockets()?);
    }

    let process_map = if cfg.resolve_process_details {
        let inode_set: BTreeSet<u64> = sock_entries.iter().map(|v| v.inode).collect();
        processes::find_processes_by_inodes(&inode_set)?
    } else {
        Default::default()
    };

    for entry in sock_entries {
        let proc_info = process_map.get(&entry.inode);
        let package_names = packages
            .uid_to_packages
            .get(&entry.uid)
            .cloned()
            .unwrap_or_default();

        out.push(Listener {
            proto: entry.proto,
            local_ip: entry.local_ip,
            port: entry.port,
            uid: entry.uid,
            inode: entry.inode,
            pid: proc_info.map(|v| v.pid),
            process_name: proc_info.map(|v| v.process_name.clone()),
            packages: package_names,
        });
    }

    out.sort_by_key(|v| (v.proto, v.port, v.uid, v.pid.unwrap_or(-1), v.inode));
    Ok(out)
}
