#!/usr/bin/env python3
"""Kotlin文件检查"""
import sys
from pathlib import Path

def main():
    print("检查Kotlin文件...")
    for f in Path('.').rglob('*.kt'):
        print(f)
    return 0

if __name__ == '__main__':
    sys.exit(main())
