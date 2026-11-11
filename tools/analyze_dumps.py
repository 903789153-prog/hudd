#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
分析 App 落盘的原始 UDP payload（Android/data/com.sys.hud/files/dumps/*.bin），
自动推断三件事：
  1) headerLen  —— 所有包共同前缀的长度（分片头）
  2) 是否加密   —— 用长度/熵/字节分布判断
  3) 可能的 opcode 位置与取值分布

用法:
  python3 analyze_dumps.py <dumps目录>
"""
import os
import sys
import math
import collections

def entropy(b):
    if not b:
        return 0.0
    c = collections.Counter(b)
    n = len(b)
    return -sum((v / n) * math.log2(v / n) for v in c.values())

def common_prefix(datas):
    if not datas:
        return 0
    base = datas[0]
    k = len(base)
    for d in datas[1:]:
        k = min(k, len(d))
        i = 0
        while i < k and d[i] == base[i]:
            i += 1
        k = i
        if k == 0:
            break
    return k

def longest_common_run(datas, min_len=1):
    """找所有包里都出现的公共子串（用于发现固定魔术字）"""
    if len(datas) < 3:
        return []
    short = min(datas, key=len)
    hits = []
    L = len(short)
    i = 0
    while i < L:
        found = None
        for j in range(L, i + min_len - 1, -1):
            pat = short[i:j]
            if all(pat in d for d in datas):
                found = pat
                break
        if found:
            hits.append(found)
            i += len(found)
        else:
            i += 1
    return hits

def guess_opcode_column(datas, prefix):
    """统计 prefix 之后第一个字节的分布：opcode 通常集中在小集合里"""
    cnt = collections.Counter()
    for d in datas:
        if len(d) > prefix:
            cnt[d[prefix]] += 1
    return cnt

def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    d = sys.argv[1]
    files = sorted(f for f in os.listdir(d) if f.endswith(".bin"))
    if not files:
        print("[!] 目录里没有 .bin")
        sys.exit(1)

    datas = []
    for f in files[:400]:
        with open(os.path.join(d, f), "rb") as fh:
            datas.append(fh.read())

    lens = [len(x) for x in datas]
    print(f"[*] 样本 {len(datas)} 个")
    print(f"[*] 长度 min={min(lens)} max={max(lens)} avg={sum(lens)//len(lens)}")
    print(f"[*] 平均熵 {sum(entropy(x) for x in datas)/len(datas):.3f} bits/byte")
    print("    熵 > 7.5 → 加密或压缩；6.5~7.5 → 可能是混淆的明文结构；< 6.5 → 明文为主")

    pf = common_prefix(datas)
    print(f"[*] 公共前缀长度 = {pf}  → 建议 headerLen ≈ {pf}")

    runs = longest_common_run(datas, 2)
    if runs:
        print("[*] 公共子串（可能是魔术字/固定字段）:")
        for r in runs[:10]:
            print("     ", r.hex(), repr(r[:16]))

    col = guess_opcode_column(datas, pf)
    if col:
        top = col.most_common(8)
        uniq = len(col)
        print(f"[*] 偏移 {pf} 处的字节分布（候选 opcode，{uniq} 种取值）:")
        for b, c in top:
            print(f"      0x{b:02x}  x{c}")
        if uniq <= max(4, len(datas) // 3):
            print(f"      → 唯一值 {uniq}/{len(datas)}，取值高度集中，这个位置很可能就是 opcode 字段")
        else:
            print(f"      → 唯一值 {uniq}/{len(datas)}，接近全随机，说明还在密文区；先解出加密再定 opcode")

    # 逐位看前 32 字节是否高频相同（找固定头）
    print("[*] 前 32 字节各样例取值（十六进制）:")
    n = min(len(datas), 5)
    for d_ in datas[:n]:
        print("     ", d_[:32].hex())

    print()
    print("[*] 下一步：")
    print("    1) 若 headerLen>0，把 App 里的「分片头长度」设成它")
    print("    2) 若熵高，先逆出加密：从 libil2cpp/libtprt 里找 XOR 表或 XXTEA，")
    print("       用 ProtoParser.XorCipher / XxteaCipher 接进去")
    print("    3) 解出明文后重跑本脚本，opcode 会变成明显的少数取值，")
    print("       再按 0x21/0x22/0x23 的顺序试 UNIT / CAMERA / SELF")

if __name__ == "__main__":
    main()
