#!/usr/bin/env python3
"""最终综合检查脚本"""
import subprocess
import sys
import json
import re
from pathlib import Path

def run_cmd(cmd, cwd=None):
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True, cwd=cwd)
    return result.stdout + result.stderr

def check_kotlin_files():
    kt_files = list(Path('.').rglob('*.kt'))
    print(f"Kotlin文件数量: {len(kt_files)}")
    issues = []
    for f in kt_files:
        content = f.read_text()
        if 'enumEntries' in content:
            issues.append(f"enumEntries in {f}")
    return issues

def check_rust_files():
    rs_files = list(Path('.').rglob('*.rs'))
    print(f"Rust文件数量: {len(rs_files)}")
    return []

def main():
    print("=== 最终综合检查 ===")
    issues = []
    issues.extend(check_kotlin_files())
    issues.extend(check_rust_files())
    if issues:
        print("发现的问题:")
        for issue in issues:
            print(f"  - {issue}")
        return 1
    print("✓ 所有检查通过!")
    return 0

if __name__ == '__main__':
    sys.exit(main())
