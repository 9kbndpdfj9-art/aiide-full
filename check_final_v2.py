#!/usr/bin/env python3
from pathlib import Path

def main():
    print("=== 最终检查 v2 ===")
    for f in Path('.').rglob('*.kt'):
        print(f)
    return 0

if __name__ == '__main__':
    main()