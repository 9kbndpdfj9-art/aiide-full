#!/usr/bin/env python3
import subprocess
import sys
from pathlib import Path

def main():
    print("=== 调试脚本2 ===")
    files = [
        'android/app/src/main/java/com/aiide/Bridge.kt',
        'android/app/src/main/java/com/aiide/ModelRouter.kt',
        'android/app/src/main/java/com/aiide/SkillManager.kt',
    ]
    for f in files:
        if Path(f).exists():
            print(f"✓ {f}")
        else:
            print(f"✗ {f} 不存在")
    return 0

if __name__ == '__main__':
    sys.exit(main())