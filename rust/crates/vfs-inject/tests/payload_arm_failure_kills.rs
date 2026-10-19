//! If the early payload cannot be armed, the suspended target must be killed,
//! not resumed: resumed, it would run with nothing virtualising it.
//!
//! The "payload" is a text file, so `CreateProcess` succeeds and arming fails
//! on the PE parse. The shim DLL is a private copy of `vproxy.dll`, so the
//! payload copy `run_target_with_shim` makes beside the shim cannot touch a
//! real artifact.
//!
//! The early-payload-activation path (`VFS_PAYLOAD_ACTIVATION`) only.
//!
//! Single-test binary: it sets a process-global env switch and
//! `run_target_with_shim` builds the child's environment from this process's.
#![allow(unsafe_code)]
mod common;

use std::time::Duration;
use vfs_inject::{run_target_with_shim, InjectError, RunConfig};

#[test]
fn a_payload_that_cannot_be_armed_gets_the_process_killed() {
    std::env::set_var(vfs_env::PAYLOAD_ACTIVATION, "1");
    let base = std::env::temp_dir().join(format!("vfs-arm-fail-{}", std::process::id()));
    let root = base.join("gameroot");
    let _ = std::fs::remove_dir_all(&base);
    std::fs::create_dir_all(&root).unwrap();
    let dll = base.join("vproxy.dll");
    std::fs::copy(common::locate_artifact("vproxy.dll"), &dll).unwrap();
    let payload = base.join("not-a-pe.dll");
    std::fs::write(&payload, b"this is not a PE image").unwrap();
    let config_path = base.join("shim.cfg");
    std::fs::write(
        &config_path,
        vfs_shim::encode_config(root.to_str().unwrap()),
    )
    .unwrap();
    let output_path = base.join("probe-out.bin");

    let result = run_target_with_shim(RunConfig {
        target_exe: env!("CARGO_BIN_EXE_vfs-probe").to_string(),
        current_dir: None,
        args: vec![
            root.join("x.bin").to_str().unwrap().to_string(),
            output_path.to_str().unwrap().to_string(),
        ],
        dll_path: dll.to_str().unwrap().to_string(),
        config_path: config_path.to_str().unwrap().to_string(),
        ready_path: base.join("ready.flag").to_str().unwrap().to_string(),
        ready_timeout: Duration::from_secs(3),
        payload_path: payload.to_str().unwrap().to_string(),
        preinit_redirects: vec![],
        detach: false,
        env_set: Vec::new(),
        env_remove: Vec::new(),
        virtual_image: None,
        virtual_cwd: None,
        force_injection: false,
    });

    assert!(
        matches!(
            result,
            Err(InjectError::PeParse) | Err(InjectError::PayloadRead)
        ),
        "expected an arming error; got {result:?}"
    );
    for _ in 0..40 {
        if output_path.exists() {
            break;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    assert!(
        !output_path.exists(),
        "the target was resumed after arming failed"
    );
    // Not merely unrun: gone. A parked process satisfies the check above.
    common::assert_no_child_processes();
    let _ = std::fs::remove_dir_all(&base);
}
