#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""简易 Java 词法体检：字符串/注释跳过，括号配平，字符字面量完整。
没有 javac 的时候先挡一层低级错误。"""
import sys, glob, io

PAIR = {'}': '{', ')': '(', ']': '['}


def check(path):
    s = io.open(path, encoding='utf-8').read()
    i, n = 0, len(s)
    line = 1
    stack = []
    errs = []
    while i < n:
        c = s[i]
        if c == '\n':
            line += 1
            i += 1
            continue
        # 注释
        if c == '/' and i + 1 < n and s[i + 1] == '/':
            j = s.find('\n', i)
            i = n if j < 0 else j
            continue
        if c == '/' and i + 1 < n and s[i + 1] == '*':
            j = s.find('*/', i + 2)
            if j < 0:
                errs.append('line %d: 注释没闭合' % line)
                break
            line += s.count('\n', i, j)
            i = j + 2
            continue
        # 文本块 """..."""
        if c == '"' and s.startswith('"""', i):
            j = s.find('"""', i + 3)
            if j < 0:
                errs.append('line %d: 文本块没闭合' % line)
                return errs
            line += s.count('\n', i, j)
            i = j + 3
            continue
        # 字符串
        if c == '"':
            j = i + 1
            while j < n:
                if s[j] == '\\':
                    j += 2
                    continue
                if s[j] == '"':
                    break
                if s[j] == '\n':
                    errs.append('line %d: 字符串跨行没闭合' % line)
                    return errs
                j += 1
            if j >= n:
                errs.append('line %d: 字符串到文件尾没闭合' % line)
                return errs
            i = j + 1
            continue
        # 字符
        if c == "'":
            j = i + 1
            while j < n:
                if s[j] == '\\':
                    j += 2
                    continue
                if s[j] == "'":
                    break
                j += 1
            if j >= n:
                errs.append('line %d: 字符字面量没闭合' % line)
                return errs
            i = j + 1
            continue
        if c in '{([':
            stack.append((c, line))
        elif c in '})]':
            if not stack:
                errs.append('line %d: 多出来的 %s' % (line, c))
            else:
                o, ol = stack.pop()
                if o != PAIR[c]:
                    errs.append('line %d: %s 对不上 line %d 的 %s' % (line, c, ol, o))
        i += 1
    for o, ol in stack:
        errs.append('line %d: %s 没闭合' % (ol, o))
    return errs


bad = 0
for f in sorted(sys.argv[1:] or glob.glob('app/src/main/java/com/rxw/xhsspy/*.java')):
    e = check(f)
    if e:
        bad += 1
        print('✗', f)
        for x in e[:8]:
            print('   ', x)
    else:
        print('✓', f)
print('---', '全过' if not bad else '%d 个文件有问题' % bad)
sys.exit(1 if bad else 0)
