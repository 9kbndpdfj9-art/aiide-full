#!/usr/bin/env python3
import subprocess
import sys
import json
from pathlib import Path

def run_command(cmd):
    try:
        result = subprocess.run(
            cmd, 
            shell=True, 
            capture_output=True, 
            text=True, 
            timeout=30
        )
        return result.returncode, result.stdout, result.stderr
    except subprocess.TimeoutExpired:
        return -1, '', 'Command timed out'
    except Exception as e:
        return -1, '', str(e)

def check_gradle():
    print("Checking Gradle build...")
    returncode, stdout, stderr = run_command('./gradlew assembleDebug --dry-run 2>&1')
    
    if returncode == 0:
        print("✓ Gradle configuration is valid")
        return True
    else:
        print(f"✗ Gradle configuration error:")
        print(stderr[:500])
        return False

def check_kotlin_syntax():
    print("\nChecking Kotlin syntax...")
    kotlin_dir = Path('android/app/src/main/java/com/aiide')
    
    if not kotlin_dir.exists():
        print("✗ Kotlin directory not found")
        return False
    
    kotlin_files = list(kotlin_dir.glob('*.kt'))
    print(f"Found {len(kotlin_files)} Kotlin files")
    
    issues = []
    for file_path in kotlin_files:
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                content = f.read()
            
            # Basic syntax checks
            if content.count('{') != content.count('}'):
                issues.append(f"{file_path.name}: Unbalanced braces")
            if content.count('(') != content.count(')'):
                issues.append(f"{file_path.name}: Unbalanced parentheses")
        except Exception as e:
            issues.append(f"{file_path}: {str(e)}")
    
    if issues:
        print(f"✗ Found {len(issues)} syntax issues:")
        for issue in issues[:10]:
            print(f"   - {issue}")
        return False
    else:
        print("✓ All Kotlin files pass basic syntax checks")
        return True

def main():
    print("=" * 50)
    print("AI IDE Final Verification")
    print("=" * 50 + "\n")
    
    checks = [
        check_kotlin_syntax(),
    ]
    
    print("\n" + "=" * 50)
    if all(checks):
        print("✓ All checks passed!")
        sys.exit(0)
    else:
        print("✗ Some checks failed")
        sys.exit(1)

if __name__ == '__main__':
    main()
