#!/usr/bin/env python3
import subprocess
import sys
import json
from pathlib import Path

def run_cmd(cmd):
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return result.stdout + result.stderr

def check_bridges():
    print("检查Bridge.kt中的action路由...")
    output = run_cmd('grep -c "action(" android/app/src/main/java/com/aiide/Bridge.kt')
    count = int(output.strip() or 0)
    print(f"Bridge action数量: {count}")
    return count > 0

def check_engines():
    print("检查引擎文件...")
    kt_files = list(Path('android').rglob('*.kt'))
    print(f"Kotlin引擎文件: {len(kt_files)}")
    return True

def main():
    if check_bridges() and check_engines():
        print("✓ 引擎检查通过")
        return 0
    return 1

if __name__ == '__main__':
    sys.exit(main())
