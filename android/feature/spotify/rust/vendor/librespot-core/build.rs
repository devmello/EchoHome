// VESPER PATCH: librespot-core 0.8.0's build script ran vergen-gitcl, which (a) fails to build
// with the vergen-lib versions resolved today and (b) would stamp EchoHome's own git sha. These are
// the values of the v0.8.0 release (commit d36f9f1907e8, .cargo_vcs_info.json), so the build is
// reproducible and the version librespot reports to Spotify is the real one.
fn main() {
    println!("cargo:rustc-env=VERGEN_GIT_SHA=d36f9f1");
    println!("cargo:rustc-env=VERGEN_GIT_COMMIT_DATE=2025-11-10");
    println!("cargo:rustc-env=VERGEN_BUILD_DATE=2025-11-10");
    let build_id = std::env::var("SOURCE_DATE_EPOCH").unwrap_or_else(|_| "vesper01".to_owned());
    println!("cargo:rustc-env=LIBRESPOT_BUILD_ID={build_id}");
    println!("cargo:rerun-if-changed=build.rs");
}
