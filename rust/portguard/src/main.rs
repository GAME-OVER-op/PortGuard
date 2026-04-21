mod config;
mod daemon;
mod detector;
mod discovery;
mod firewall;
mod logger;
mod policy;
mod procfs;
mod reaction;
mod runtime;
mod shell;
mod types;

use anyhow::Result;
use std::env;

#[derive(Debug, Clone, Default)]
struct CliArgs {
    once: bool,
}

impl CliArgs {
    fn parse() -> Result<Self> {
        let mut out = Self::default();
        for arg in env::args().skip(1) {
            match arg.as_str() {
                "--once" => out.once = true,
                "--help" | "-h" => {
                    println!(
                        "PortGuard\n\
                         Usage:\n\
                           portguard [--once]\n\n\
                         Settings path (hardcoded):\n\
                           {}\n",
                        config::SETTINGS_DIR
                    );
                    std::process::exit(0);
                }
                other => {
                    anyhow::bail!("unknown argument: {other}");
                }
            }
        }
        Ok(out)
    }
}

fn main() {
    if let Err(err) = real_main() {
        logger::error("main", &format!("{err:#}"));
        std::process::exit(1);
    }
}

fn real_main() -> Result<()> {
    let args = CliArgs::parse()?;
    let cfg = config::load_module_settings()?;
    logger::info(
        "main",
        &format!(
            "starting PortGuard: settings_dir={}, config={}, once={}",
            config::SETTINGS_DIR,
            config::CONFIG_JSON_PATH,
            args.once
        ),
    );
    daemon::run(cfg, args.once)
}
