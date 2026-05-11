#!/usr/bin/env python3
"""最终检查脚本"""
import subprocess
import sys
from pathlib import Path

def main():
    print("=== 最终检查 ===")
    kt_count = len(list(Path('.').rglob('*.kt')))
    print(f"Kotlin文件总数: {kt_count}")
    return 0

if __name__ == '__main__':
    sys.exit(main())
