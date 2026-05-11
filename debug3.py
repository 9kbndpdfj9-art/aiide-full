#!/usr/bin/env python3
import subprocess
import sys
from pathlib import Path

def run_cmd(cmd):
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return result.stdout + result.stderr

def check_gradle():
    print("检查Gradle配置...")
    build_gradle = Path('android/app/build.gradle.kts')
    if build_gradle.exists():
        print(f"✓ build.gradle.kts 存在")
        return True
    return False

def main():
    check_gradle()
    print("检查完成")
    return 0

if __name__ == '__main__':
    sys.exit(main())
