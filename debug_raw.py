#!/usr/bin/env python3
import subprocess
import sys
from pathlib import Path

def main():
    print("=== 原始调试脚本 ===")
    result = subprocess.run(['find', '.', '-name', '*.kt', '-type', 'f'], 
                            capture_output=True, text=True)
    print(result.stdout)
    return 0

if __name__ == '__main__':
    sys.exit(main())
