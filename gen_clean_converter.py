import opencc

tw2s = opencc.OpenCC('tw2sp')

with open('C:/Users/Korit/ai/ime/app/src/main/assets/dict_tw.txt', 'r', encoding='utf-8') as f:
    words = [line.strip().split('\t')[0] for line in f if line.strip()]

trad_chars = set(''.join(words))

# 逐一檢查單字映射，只保留 BMP (長度為 1 且不是 surrogate pair 的標準 Unicode 字符)
clean_trad = []
clean_simp = []

for c in sorted(trad_chars):
    if len(c) == 1 and ord(c) < 0x10000:
        if c in '"\\\'\n\r': continue
        s = tw2s.convert(c)
        if len(s) == 1 and ord(s) < 0x10000 and s not in '"\\\'\n\r':
            if s != c:
                clean_trad.append(c)
                clean_simp.append(s)

trad_str = ''.join(clean_trad)
simp_str = ''.join(clean_simp)

print(f"Clean single-char mappings count: {len(clean_trad)}, length check: {len(trad_str)} == {len(simp_str)}")

with open('C:/Users/Korit/ai/ime/app/src/main/java/com/bopomofo/t9ime/engine/ChineseConverter.kt', 'w', encoding='utf-8') as f:
    f.write('package com.bopomofo.t9ime.engine\n\n')
    f.write('object ChineseConverter {\n')
    f.write('    private const val TRAD_CHARS = "' + trad_str + '"\n')
    f.write('    private const val SIMP_CHARS = "' + simp_str + '"\n')
    f.write('    private val MAP = HashMap<Char, Char>(' + str(len(clean_trad)) + ')\n\n')
    f.write('    init {\n')
    f.write('        for (i in TRAD_CHARS.indices) {\n')
    f.write('            MAP[TRAD_CHARS[i]] = SIMP_CHARS[i]\n')
    f.write('        }\n')
    f.write('    }\n\n')
    f.write('    fun toSimplified(text: String): String {\n')
    f.write('        val sb = StringBuilder(text.length)\n')
    f.write('        for (ch in text) {\n')
    f.write('            sb.append(MAP[ch] ?: ch)\n')
    f.write('        }\n')
    f.write('        return sb.toString()\n')
    f.write('    }\n')
    f.write('}\n')

print("Successfully written 100% aligned ChineseConverter.kt")
