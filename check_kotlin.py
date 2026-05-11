#!/usr/bin/env python3
import subprocess
import sys
import os
import json
import re
from pathlib import Path

def extract_imports(content):
    imports = set()
    for line in content.split('\n'):
        line = line.strip()
        if line.startswith('import '):
            imports.add(line.split('import ')[1].split('.')[0])
    return imports

def find_missing_imports(file_path, content):
    missing = []
    imports = extract_imports(content)
    
    # Check for common Kotlin types
    if re.search(r'\b(List|Map|Set|ArrayList|HashMap|HashSet)\b', content):
        if 'kotlin.collections' not in imports and 'kotlin' not in imports:
            missing.append('kotlin.collections.*')
    
    if re.search(r'\b(Int|Long|Double|Float|Boolean|String)\b', content):
        if 'kotlin' not in imports:
            missing.append('kotlin.*')
    
    return missing

def check_kotlin_file(file_path):
    issues = []
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
        
        # Check for common errors
        if content.count('{') != content.count('}'):
            issues.append(f"Unbalanced braces: {content.count('{')} open vs {content.count('}')} close")
        
        if content.count('(') != content.count(')'):
            issues.append(f"Unbalanced parentheses: {content.count('(')} open vs {content.count(')')} close")
        
        # Check for missing imports
        missing = find_missing_imports(file_path, content)
        if missing:
            issues.append(f"Possible missing imports: {missing}")
        
        # Check for TODO/FIXME without proper context
        if 'TODO' in content or 'FIXME' in content:
            issues.append("Contains TODO/FIXME markers")
        
        return {
            'file': file_path,
            'issues': issues,
            'has_issues': len(issues) > 0
        }
    except Exception as e:
        return {
            'file': file_path,
            'issues': [f'Error reading file: {str(e)}'],
            'has_issues': True
        }

def main():
    kotlin_dir = Path('android/app/src/main/java/com/aiide')
    
    if not kotlin_dir.exists():
        print("Kotlin directory not found")
        sys.exit(1)
    
    kotlin_files = list(kotlin_dir.glob('*.kt'))
    print(f"Checking {len(kotlin_files)} Kotlin files...\n")
    
    results = []
    for file_path in sorted(kotlin_files):
        result = check_kotlin_file(file_path)
        results.append(result)
        
        if result['has_issues']:
            print(f"❌ {result['file']}")
            for issue in result['issues']:
                print(f"   - {issue}")
        else:
            print(f"✓ {result['file']}")
    
    files_with_issues = sum(1 for r in results if r['has_issues'])
    print(f"\nSummary: {len(results)} files checked, {files_with_issues} with issues")

if __name__ == '__main__':
    main()
