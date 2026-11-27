// Compile the only shader requiring an immutable combined YCbCr sampler.
// Naga's ordinary WGSL bindings are intentionally not used for this ABI.
use std::{env,path::{Path,PathBuf},process::Command};

fn ndk_glslc()->Option<PathBuf>{
    let root=env::var_os("ANDROID_NDK_HOME").or_else(||env::var_os("ANDROID_NDK_ROOT"))?;
    let dir=PathBuf::from(root).join("shader-tools");
    let mut candidates=std::fs::read_dir(dir).ok()?.filter_map(|e|e.ok()).map(|e|e.path())
        .filter_map(|p|{let exe=p.join(if cfg!(windows){"glslc.exe"}else{"glslc"});exe.is_file().then_some(exe)})
        .collect::<Vec<_>>();
    candidates.sort();
    candidates.into_iter().find(|p|Command::new(p).arg("--version").output().is_ok_and(|o|o.status.success()))
}
fn compile(tool:&Path,glslang:bool,src:&Path,dst:&Path){
    let mut cmd=Command::new(tool);
    if glslang{cmd.args(["-V","--target-env","vulkan1.1","-S","comp"]);}
    else{cmd.args(["--target-env=vulkan1.1","-fshader-stage=compute","-O"]);}
    let result=cmd.arg("-o").arg(dst).arg(src).output()
        .unwrap_or_else(|e|panic!("could not launch {}: {e}",tool.display()));
    if !result.status.success(){panic!("AHB shader compilation failed: {}",String::from_utf8_lossy(&result.stderr));}
}
fn main(){
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=shaders/ahb_convert.comp");
    for e in ["ANDROID_NDK_HOME","ANDROID_NDK_ROOT","SVP_GLSLC"]{println!("cargo:rerun-if-env-changed={e}");}
    if env::var("CARGO_CFG_TARGET_OS").as_deref()!=Ok("android")||env::var_os("CARGO_FEATURE_VULKAN").is_none(){return;}
    let src=PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap()).join("shaders/ahb_convert.comp");
    let dst=PathBuf::from(env::var_os("OUT_DIR").unwrap()).join("ahb_convert.spv");
    if let Some(tool)=env::var_os("SVP_GLSLC").map(PathBuf::from).or_else(ndk_glslc){
        println!("cargo:rerun-if-changed={}",tool.display());
        compile(&tool,false,&src,&dst);
    }else if Command::new("glslc").arg("--version").output().is_ok_and(|o|o.status.success()){
        compile(Path::new("glslc"),false,&src,&dst);
    }else{
        // Host cross-check CI uses the system GLSL compiler.
        compile(Path::new("glslangValidator"),true,&src,&dst);
    }
    let bytes=std::fs::read(dst).expect("missing AHB SPIR-V");
    assert!(bytes.len()>=20&&bytes.len()%4==0&&bytes[..4]==[3,2,35,7],"invalid AHB SPIR-V");
}
