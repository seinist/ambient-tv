#!/usr/bin/env python3
"""Compile the pinned PPSSPP scheduler with production replacements and a fake Android affinity."""
import json
from pathlib import Path
import subprocess
import tempfile
import urllib.request
from core_hotpath_remaining import PATCHES
from core_hotpath_patches import replace_once

ROOT = Path(__file__).resolve().parents[1]
def main():
    core = next(c for c in json.loads((ROOT/"cores/cores.json").read_text())["cores"] if c["core"]=="ppsspp")
    upstream = core["repo"].removeprefix("https://github.com/")
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        for relative in ("Common/Thread/ThreadManager.cpp", "Common/Thread/ThreadManager.h"):
            target = root/relative
            target.parent.mkdir(parents=True,exist_ok=True)
            with urllib.request.urlopen(f"https://raw.githubusercontent.com/{upstream}/{core['commit']}/{relative}",timeout=60) as stream:
                source = stream.read().decode()
            for file,old,new in PATCHES["ppsspp"]:
                if file==relative:
                    source=replace_once(source,old,new,relative)
            target.write_text(source)
        (root/"ppsspp_config.h").write_text("#pragma once\n#define PPSSPP_PLATFORM(x) 0\n")
        (root/"Common/Log.h").write_text("""#pragma once
#include <cassert>
#define _assert_(x) assert(x)
#define _assert_msg_(x, ...) assert(x)
#define WARN_LOG(...) ((void)0)
#define INFO_LOG(...) ((void)0)
""")
        (root/"Common/Thread/ThreadUtil.h").write_text("""#pragma once
inline void SetCurrentThreadName(const char*) {}
inline void AttachThreadToJNI() {}
inline void DetachThreadFromJNI() {}
""")
        (root/"affinity.h").write_text("""#pragma once
#include <atomic>
#include <sched.h>
inline std::atomic<int> test_cpus{8};
inline int test_affinity(pid_t, size_t, cpu_set_t* out) {
    CPU_ZERO(out);
    for(int i=0;i<test_cpus.load();++i)CPU_SET(i,out);
    return 0;
}
#define sched_getaffinity test_affinity
""")
        (root/"stress.cpp").write_text("""#include "Common/Thread/ThreadManager.h"
#include <atomic>
#include <cassert>
#include <chrono>
#include <thread>
struct Work:Task {
    std::atomic<int>& completed;
    explicit Work(std::atomic<int>& count):completed(count){}
    TaskType Type() const override{return TaskType::CPU_COMPUTE;}
    TaskPriority Priority() const override{return TaskPriority::NORMAL;}
    void Run() override{completed.fetch_add(1,std::memory_order_release);}
};
static void wait(std::atomic<int>& completed,int expected) {
    const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(10);
    while(completed.load(std::memory_order_acquire)<expected &&
          std::chrono::steady_clock::now()<deadline)std::this_thread::yield();
    assert(completed.load()==expected);
}
int main() {
    for(int lifecycle=0;lifecycle<4;++lifecycle) {
        test_cpus=8;
        ThreadManager manager;
        manager.Init(8,1);
        assert(manager.GetNumLooperThreads()==6);
        std::atomic<int> completed{0};
        std::thread producer([&]{
            for(int i=0;i<12000;++i)manager.EnqueueTask(new Work(completed));
        });
        for(int i=0;i<40;++i) {
            test_cpus=i%2?8:2;
            for(int frame=0;frame<60;++frame)manager.RefreshComputeBudget();
            assert(manager.GetNumLooperThreads()==(i%2?6:1));
        }
        producer.join();wait(completed,12000);
        test_cpus=2;
        for(int frame=0;frame<60;++frame)manager.RefreshComputeBudget();
        assert(manager.GetNumLooperThreads()==1);
        manager.EnqueueTaskOnThread(5,new Work(completed)); // private pinned work still runs
        wait(completed,12001);
        manager.Teardown();
        assert(!manager.IsInitialized());
    }
}
""")
        binary=root/"stress"
        subprocess.run(["c++","-std=c++17","-O2","-pthread","-D__ANDROID__","-I",str(root),
                        "-include",str(root/"affinity.h"),str(root/"Common/Thread/ThreadManager.cpp"),
                        str(root/"stress.cpp"),"-o",str(binary)],check=True)
        subprocess.run([str(binary)],check=True,timeout=90)
if __name__=="__main__":
    main()
