#!/usr/bin/env python3
"""
自动修复 DDD 重构后的 Java import。
"""
import os
import re
from pathlib import Path

ROOT = Path("f:/ai/rag/core-agent/src/main/java/com/core/agent")

# 扫描所有类定义: 包名 -> {类名: 文件路径}
class_locations = {}
for java_file in ROOT.rglob("*.java"):
    content = java_file.read_text(encoding="utf-8")
    pkg_match = re.search(r"^package\s+([\w.]+);", content, re.MULTILINE)
    if not pkg_match:
        continue
    pkg = pkg_match.group(1)
    # 查找 public class/interface/enum 声明
    for m in re.finditer(r"\b(public\s+)?(class|interface|enum)\s+(\w+)", content):
        class_name = m.group(3)
        class_locations.setdefault(class_name, set()).add(pkg)

# java.lang 下的类不需要 import
java_lang_classes = {
    "Object", "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte",
    "Short", "Character", "Void", "Class", "Enum", "Exception", "RuntimeException",
    "Throwable", "Iterable", "Comparable", "Cloneable", "Serializable",
    "System", "StringBuilder", "StringBuffer", "Math", "Thread", "Runnable",
    "Process", "Runtime", "Package", "Module", "ClassLoader",
    "Boolean", "Character", "Number",
}

def extract_class_refs(content):
    """从代码中提取可能的外部类引用。"""
    refs = set()
    # 匹配 CamelCase 标识符
    for m in re.finditer(r"\b[A-Z][a-zA-Z0-9_]*\b", content):
        name = m.group(0)
        if name in java_lang_classes:
            continue
        refs.add(name)
    return refs

def fix_file(java_file):
    content = java_file.read_text(encoding="utf-8")
    pkg_match = re.search(r"^package\s+([\w.]+);", content, re.MULTILINE)
    if not pkg_match:
        return
    current_pkg = pkg_match.group(1)

    # 删除旧的 project import
    content = re.sub(r"import com\.core\.agent\.[\w.*]+;\n", "", content)

    # 提取类引用
    refs = extract_class_refs(content)

    # 生成需要的 import
    imports = set()
    for ref in refs:
        if ref not in class_locations:
            continue
        pkgs = class_locations[ref]
        # 过滤当前包
        external = [p for p in pkgs if p != current_pkg]
        if not external:
            continue
        if len(external) > 1:
            print(f"WARN: ambiguous class {ref} in {java_file}: {external}")
            continue
        imports.add(f"import {external.pop()}.{ref};")

    # 将 imports 按字母顺序插入到 package 之后
    import_block = "\n".join(sorted(imports))
    if import_block:
        # 找到 package 行后的第一个空行或 import/org 行
        content = re.sub(
            r"^(package\s+[\w.]+;)\n",
            r"\1\n" + import_block + "\n",
            content,
            flags=re.MULTILINE
        )

    java_file.write_text(content, encoding="utf-8")

for java_file in ROOT.rglob("*.java"):
    fix_file(java_file)

print("imports fixed")
