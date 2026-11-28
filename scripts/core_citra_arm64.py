"""Prepare the pinned Citra 2018 ARM64 A32 Dynarmic candidate.

Only the explicitly selected diagnostic profile may apply these changes.
The legacy x86_64 dependency, baseline, and public optimized channels stay intact.
"""
import argparse
from pathlib import Path
import subprocess

DYNA_REPO = "https://github.com/azahar-emu/dynarmic.git"
DYNA_PIN = "a46601580d5512d324104f985b5f0209dc980ddc"
DEPENDENCIES = {
    "externals/fmt": "7b273fbb541c7c9058800bc613f4291274b2d26e",
    "externals/mcl": "5fc4beaf331037649b10625736b41365defb4f50",
    "externals/oaknut": "94c726ce0338b054eb8cb5ea91de8fe6c19f4392",
    "externals/robin-map": "054ec5ad67440fcd65e0497e5a27ef31f53fcc7f",
}

def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()

def replace(root, path, old, new, count=1):
    file = root / path
    source = file.read_text()
    if source.count(old) != count:
        raise ValueError(f"{path}: expected {count} occurrences of {old[:80]!r}")
    file.write_text(source.replace(old, new))

def prepare(root):
    root = root.resolve()
    dependency = root / "externals/ambient-dynarmic"
    dependency.mkdir()
    git(dependency, "init", "-q")
    git(dependency, "remote", "add", "origin", DYNA_REPO)
    git(dependency, "fetch", "--depth", "1", "origin", DYNA_PIN)
    git(dependency, "checkout", "--detach", "FETCH_HEAD")
    if git(dependency, "rev-parse", "HEAD") != DYNA_PIN:
        raise ValueError("Dynarmic pin mismatch")
    git(dependency, "submodule", "update", "--init", "--depth", "1", "--", *DEPENDENCIES)
    for path, pin in DEPENDENCIES.items():
        if git(dependency / path, "rev-parse", "HEAD") != pin:
            raise ValueError(f"Dependency pin mismatch: {path}")

    # Keep Citra's old fmt target and ABI isolated from Dynarmic's newer headers.
    for path in ("CMakeLists.txt", "externals/CMakeLists.txt",
                 "src/dynarmic/CMakeLists.txt", "externals/mcl/CMakeLists.txt",
                 "externals/mcl/src/CMakeLists.txt"):
        file = dependency / path
        source = file.read_text()
        if "fmt::fmt" not in source:
            raise ValueError(f"Missing modern fmt target: {path}")
        file.write_text(source.replace("fmt::fmt", "ambient_dynarmic_fmt"))
    # Installation/export metadata is not used by this statically linked core.
    # Avoid exporting the private header-only fmt target from the legacy project.
    file = dependency / "CMakeLists.txt"
    source = file.read_text()
    marker = "include(GNUInstallDirs)\ninclude(CMakePackageConfigHelpers)"
    if source.count(marker) != 1:
        raise ValueError("Dynarmic install block changed")
    file.write_text(source.split(marker)[0])
    replace(dependency, "externals/CMakeLists.txt", "set(MCL_INSTALL ON)", "set(MCL_INSTALL OFF)")
    replace(root, "externals/CMakeLists.txt", "# Dynarmic\nif (ARCHITECTURE_x86_64)", """# Dynarmic
if (ANDROID AND ARCHITECTURE_ARM64)
    function(ambient_add_dynarmic)
        # The old Citra uses ARM64, the modern dependency uses arm64.
        set(ARCHITECTURE arm64)
        set(DYNARMIC_FRONTENDS A32)
        set(DYNARMIC_TESTS OFF)
        set(DYNARMIC_USE_BUNDLED_EXTERNALS ON)
        set(DYNARMIC_USE_PRECOMPILED_HEADERS OFF)
        set(DYNARMIC_ENABLE_NO_EXECUTE_SUPPORT ON)
        set(CMAKE_POSITION_INDEPENDENT_CODE ON)
        set(BUILD_SHARED_LIBS OFF)
        add_library(ambient_dynarmic_fmt INTERFACE)
        target_include_directories(ambient_dynarmic_fmt INTERFACE
            ${CMAKE_CURRENT_SOURCE_DIR}/ambient-dynarmic/externals/fmt/include)
        target_compile_definitions(ambient_dynarmic_fmt INTERFACE FMT_HEADER_ONLY=1)
        add_subdirectory(ambient-dynarmic)
    endfunction()
    ambient_add_dynarmic()
elseif (ARCHITECTURE_x86_64)""")
    replace(root, "src/core/CMakeLists.txt", "if (ARCHITECTURE_x86_64)\n    target_sources(core PRIVATE",
            """if (ARCHITECTURE_ARM64 OR ARCHITECTURE_x86_64)
    if (ARCHITECTURE_ARM64)
        target_compile_definitions(core PRIVATE AMBIENT_CITRA_ARM64_JIT=1)
    endif()
    target_sources(core PRIVATE""")
    replace(root, "src/core/core.cpp", "#ifdef ARCHITECTURE_x86_64",
            "#if defined(ARCHITECTURE_x86_64) || defined(AMBIENT_CITRA_ARM64_JIT)", 2)
    folder = "src/core/arm/dynarmic/"
    for name in ("arm_dynarmic.h", "arm_dynarmic.cpp", "arm_dynarmic_cp15.h"):
        file = root / (folder + name)
        file.write_text(file.read_text().replace("<dynarmic/A32/", "<dynarmic/interface/A32/"))
    replace(root, folder + "arm_dynarmic.h", "#include <dynarmic/interface/A32/a32.h>",
            "#include <dynarmic/interface/A32/a32.h>\n#include <dynarmic/interface/exclusive_monitor.h>")
    replace(root, folder + "arm_dynarmic.h", "    std::unique_ptr<DynarmicUserCallbacks> cb;",
            "    std::unique_ptr<DynarmicUserCallbacks> cb;\n    Dynarmic::ExclusiveMonitor monitor{1};")
    for name in ("arm_dynarmic_cp15.h", "arm_dynarmic_cp15.cpp"):
        file = root / (folder + name)
        source = file.read_text().replace("boost::optional", "std::optional").replace(
            "boost::none", "std::nullopt").replace("boost::blank{}", "std::monostate{}")
        if name.endswith(".h"):
            source = source.replace("#include <memory>", "#include <memory>\n#include <optional>\n#include <variant>")
        file.write_text(source)
    path = folder + "arm_dynarmic.cpp"
    replace(root, path, "#include <dynarmic/interface/A32/context.h>", "#include <array>")
    # The current API exposes registers directly instead of A32::Context.
    # Keep FPEXC in Citra's existing interpreter-owned context.
    file = root / path
    source = file.read_text()
    start = source.index("    void Reset() override {")
    end = source.index("\n};", start)
    old = source[start:end]
    new = """    void Reset() override {
        regs = {};
        ext_regs = {};
        cpsr = fpscr = fpexc = 0;
    }

    u32 GetCpuRegister(std::size_t index) const override { return regs[index]; }
    void SetCpuRegister(std::size_t index, u32 value) override { regs[index] = value; }
    u32 GetCpsr() const override { return cpsr; }
    void SetCpsr(u32 value) override { cpsr = value; }
    u32 GetFpuRegister(std::size_t index) const override { return ext_regs[index]; }
    void SetFpuRegister(std::size_t index, u32 value) override { ext_regs[index] = value; }
    u32 GetFpscr() const override { return fpscr; }
    void SetFpscr(u32 value) override { fpscr = value; }
    u32 GetFpexc() const override { return fpexc; }
    void SetFpexc(u32 value) override { fpexc = value; }
private:
    friend class ARM_Dynarmic;
    std::array<u32, 16> regs{};
    std::array<u32, 64> ext_regs{};
    u32 cpsr{}, fpscr{}, fpexc{};"""
    replace(root, path, old, new)
    replace(root, path, "    jit->SaveContext(ctx->ctx);", """    ctx->regs = jit->Regs();
    ctx->ext_regs = jit->ExtRegs();
    ctx->cpsr = jit->Cpsr();
    ctx->fpscr = jit->Fpscr();""")
    replace(root, path, "    jit->LoadContext(ctx->ctx);", """    jit->Regs() = ctx->regs;
    jit->ExtRegs() = ctx->ext_regs;
    jit->SetCpsr(ctx->cpsr);
    jit->SetFpscr(ctx->fpscr);
    jit->ClearExclusiveState();""")
    replace(root, path, "static_cast<std::size_t>(exception), pc, MemoryReadCode(pc));",
            "static_cast<std::size_t>(exception), pc, MemoryReadCode(pc).value_or(0));")
    replace(root, path, "    config.callbacks = cb.get();\n    config.page_table = &current_page_table->pointers;",
            """    config.callbacks = cb.get();
    config.global_monitor = &monitor;
    config.processor_id = 0;
    config.arch_version = Dynarmic::A32::ArchVersion::v6K;
    config.page_table = current_page_table ? &current_page_table->pointers : nullptr;
    // Direct page-table accesses are safe for this legacy page mapping.
    // MMIO and cross-page accesses use the existing memory callbacks.
    config.detect_misaligned_access_via_page_table = 16 | 32 | 64;
    config.only_detect_misalignment_via_page_table_on_page_boundary = true;
    config.code_cache_size = 32 * 1024 * 1024;
    config.unsafe_optimizations = false;
    // No contiguous, alias-coherent 4 GiB mapping exists in Citra 2018.
    config.fastmem_pointer = std::nullopt;""")
    # The single emulated CPU serializes guest reads/writes. Do not execute
    # compare-and-write on MMIO, rasterizer-cached pages or page-straddling data.
    marker = "    void InterpreterFallback(VAddr pc, std::size_t num_instructions) override {"
    methods = []
    for bits in (8, 16, 32, 64):
        methods.append(f"""    bool MemoryWriteExclusive{bits}(VAddr addr, std::uint{bits}_t value,
                                      std::uint{bits}_t expected) override {{
        if ((addr & (sizeof(value) - 1)) != 0 ||
            !Memory::GetCurrentPageTable() ||
            !Memory::GetCurrentPageTable()->pointers[addr >> Memory::PAGE_BITS])
            return false;
        if (Memory::Read{bits}(addr) != expected)
            return false;
        Memory::Write{bits}(addr, value);
        return true;
    }}
""")
    replace(root, path, marker, "\n".join(methods) + "\n" + marker)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    prepare(parser.parse_args().root)

if __name__ == "__main__":
    main()
