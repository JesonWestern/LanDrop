#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在中文路径工程里跑 Android 单元测试。

为什么不用 `gradlew test`：
    本项目位于 `D:/agentproject/内网传输`（非 ASCII）。Gradle 9.4.1 在 Windows 上
    给测试 worker 子进程传递工作目录/类路径时会按错误字符集编码，导致 worker 拿到
    形如 `D:\\agentproject\\鍐呯綉浼犺緭` 的路径，报
    `Failed to execute test class: ... ClassNotFoundException`。
    编译、打包都不受影响，只有 fork 出来的测试进程受影响。
本脚本改用 `java -cp` 直接拉起 JUnit，进程参数通过 CreateProcessW 传递，不受影响。

用法：
    python tools/android_unit_test.py
"""
import glob
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID = os.path.join(ROOT, "android")
GRADLE = os.path.join(ANDROID, "gradlew.bat")
GRADLE_HOME = os.path.join(os.path.expanduser("~"), ".gradle")
MODULES = os.path.join(GRADLE_HOME, "caches", "modules-2", "files-2.1")

TEST_CLASSES = os.path.join(
    ANDROID, "app", "build", "intermediates", "built_in_kotlinc",
    "debugUnitTest", "compileDebugUnitTestKotlin", "classes")
MAIN_CLASSES = os.path.join(
    ANDROID, "app", "build", "intermediates", "built_in_kotlinc",
    "debug", "compileDebugKotlin", "classes")


def find_jar(pattern):
    hits = [p for p in glob.glob(os.path.join(MODULES, pattern), recursive=True)
            if "-sources" not in p and "-javadoc" not in p]
    if not hits:
        return None
    return sorted(hits)[-1]


def main():
    print("==> 编译主程序与单元测试")
    r = subprocess.run(
        [GRADLE, ":app:compileDebugUnitTestKotlin", "--offline", "--console=plain"],
        cwd=ANDROID,
    )
    if r.returncode != 0:
        print("编译失败")
        return r.returncode

    jars = [
        find_jar("org.jetbrains.kotlin/kotlin-stdlib/*/*/kotlin-stdlib-*.jar"),
        find_jar("junit/junit/*/*/junit-*.jar"),
        find_jar("org.hamcrest/hamcrest-core/*/*/hamcrest-core-*.jar"),
    ]
    jars = [j for j in jars if j]
    if not any("junit-" in j for j in jars):
        print("没找到 junit jar，请先跑一次 gradlew :app:assembleDebug（联网拉依赖）")
        return 1

    classes = [c for c in (TEST_CLASSES, MAIN_CLASSES) if os.path.isdir(c)]
    if not classes or not os.path.isdir(TEST_CLASSES):
        print("没找到单测编译产物，请确认编译成功")
        return 1

    cp = os.pathsep.join(classes + jars)
    tests = []
    for f in glob.glob(os.path.join(TEST_CLASSES, "**", "*Test.class"), recursive=True):
        rel = os.path.relpath(f, TEST_CLASSES).replace("\\", "/")
        tests.append(rel[:-len(".class")].replace("/", "."))
    if not tests:
        print("未发现测试类")
        return 1

    print("==> 运行 %d 个测试类" % len(tests))
    cmd = ["java", "-cp", cp, "org.junit.runner.JUnitCore"] + sorted(tests)
    return subprocess.run(cmd).returncode


if __name__ == "__main__":
    sys.exit(main())
