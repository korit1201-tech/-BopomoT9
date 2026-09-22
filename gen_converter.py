import opencc

tw2s = opencc.OpenCC('tw2sp')

with open('C:/Users/Korit/ai/ime/app/src/main/assets/dict_tw.txt', 'r', encoding='utf-8') as f:
    words = [line.strip().split('\t')[0] for line in f if line.strip()]

trad_chars = set(''.join(words))
mapping = {}
for c in trad_chars:
    if c in '"\\\'\n\r':
        continue
    s = tw2s.convert(c)
    if s != c and s not in '"\\\'\n\r':
        mapping[c] = s

trad_str = ''.join(mapping.keys())
simp_str = ''.join(mapping.values())

with open('C:/Users/Korit/ai/ime/app/src/main/java/com/bopomofo/t9ime/engine/ChineseConverter.kt', 'w', encoding='utf-8') as f:
    f.write('package com.bopomofo.t9ime.engine\n\n')
    f.write('object ChineseConverter {\n')
    f.write('    private const val TRAD_CHARS = "' + trad_str + '"\n')
    f.write('    private const val SIMP_CHARS = "' + simp_str + '"\n')
    f.write('    private val MAP = HashMap<Char, Char>(' + str(len(mapping)) + ')\n\n')
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

print(f'Done: {len(mapping)} chars mapped')
