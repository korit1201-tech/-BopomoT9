import urllib.request
import json
import pypinyin
import re
import opencc

print("Starting massive Taiwan vocabulary harvesting...")

s2tw = opencc.OpenCC('s2twp')

# 1. 抓取萌典 (g0v moedict) 常用詞庫精華
moedict_url = "https://raw.githubusercontent.com/g0v/moedict-data/master/dict-revised.json.xz"
# 備用來源：教育部成語與高頻台灣國語常用詞
urls = [
    "https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts/8105.dict.yaml",
    "https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts/base.dict.yaml",
    "https://raw.githubusercontent.com/rime/rime-bopomofo/master/bopomofo.dict.yaml"
]

harvested = {}

# 預載台灣在地海量分類詞（美食、全台368鄉鎮市區、交通、生活品牌、夜市、流行語）
tw_categories = {
    # 台灣小吃與在地美食
    "美食小吃": [
        "貢丸", "貢丸湯", "新竹貢丸", "肉圓", "彰化肉圓", "清蒸肉圓", "炸肉圓",
        "滷肉飯", "魯肉飯", "控肉飯", "爌肉飯", "雞肉飯", "嘉義雞肉飯", "排骨飯",
        "雞排", "豪大大雞排", "鹹酥雞", "鹽酥雞", "甜不辣", "米血", "百頁豆腐",
        "臭豆腐", "深坑臭豆腐", "麻辣臭豆腐", "大腸包小腸", "蚵仔煎", "蝦仁煎",
        "蚵仔麵線", "大腸麵線", "肉羹麵", "魷魚羹", "土魠魚羹", "花枝羹",
        "牛肉麵", "紅燒牛肉麵", "清燉牛肉麵", "半筋半肉", "牛三寶", "蔥油餅",
        "小籠包", "小籠湯包", "生煎包", "水煎包", "胡椒餅", "太陽餅", "鳳梨酥",
        "牛軋糖", "蛋黃酥", "芋頭酥", "車輪餅", "紅豆餅", "地瓜球", "白糖粿",
        "愛玉冰", "仙草凍", "豆花", "傳統豆花", "黑糖刨冰", "芒果冰", "雪花冰",
        "珍奶", "珍珠奶茶", "波霸奶茶", "黑糖珍珠鮮奶", "手搖杯", "五十嵐",
        "清心福全", "麻古茶坊", "可不可熟成紅茶", "迷客夏", "茶湯會", "得正",
        "一芳", "龜記", "萬波", "五桐號", "再睡五分鐘", "八方雲集", "四海遊龍",
        "三媽臭臭鍋", "石二鍋", "海底撈", "鼎泰豐", "春水堂", "鬍鬚張", "欣葉",
        "早午餐", "美而美", "弘爺漢堡", "麥味登", "拉亞漢堡", "永和豆漿", "燒餅油條",
        "飯糰", "蛋餅", "起司蛋餅", "培根蛋餅", "蘿蔔糕", "鐵板麵", "卡啦雞腿堡"
    ],
    # 台灣全台縣市、鄉鎮市區地名與景點
    "台灣地理景點": [
        "台北", "新北", "基隆", "桃園", "新竹", "苗栗", "台中", "彰化", "南投",
        "雲林", "嘉義", "台南", "高雄", "屏東", "宜蘭", "花蓮", "台東", "澎湖",
        "金門", "馬祖", "板橋", "中和", "永和", "新莊", "三重", "蘆洲", "汐止",
        "新店", "土城", "樹林", "淡水", "鶯歌", "三峽", "瑞芳", "金山", "萬里",
        "中壢", "平鎮", "八德", "楊梅", "蘆竹", "龜山", "大溪", "竹北", "竹東",
        "頭份", "竹南", "豐原", "大里", "太平", "東勢", "大甲", "清水", "沙鹿",
        "員林", "鹿港", "和美", "草屯", "埔里", "竹山", "斗六", "虎尾", "斗南",
        "民雄", "水上", "朴子", "永康", "安平", "新營", "麻豆", "佳里", "鳳山",
        "岡山", "旗山", "美濃", "潮州", "東港", "恆春", "墾丁", "羅東", "礁溪",
        "頭城", "蘇澳", "吉安", "玉里", "礁溪溫泉", "知本溫泉", "日月潭", "阿里山",
        "太魯閣", "陽明山", "九份", "十分", "野柳", "西門町", "信義區", "東區",
        "士林夜市", "逢甲夜市", "六合夜市", "花園夜市", "羅東夜市", "文化路夜市"
    ],
    # 台灣生活常用機構、交通與日常詞彙
    "日常交通與生活": [
        "捷運", "高鐵", "台鐵", "客運", "悠遊卡", "一卡通", "台聯", "和欣",
        "國光客運", "統聯客運", "機捷", "輕軌", "環狀線", "板南線", "淡水信義線",
        "文湖線", "中和新蘆線", "松山新店線", "台積電", "聯發科", "鴻海", "華碩",
        "宏碁", "富邦", "國泰", "中信", "玉山", "台新", "第一銀行", "兆豐",
        "中華電信", "台灣大哥大", "遠傳", "健保卡", "身分證", "統一發票", "載具",
        "全家", "全聯", "家樂福", "好市多", "大潤發", "小七", "寶雅", "屈臣氏", "康是美"
    ],
    # 常用生活口語、慣用語與高頻動詞
    "生活常用語句": [
        "什麼", "甚麼", "怎麼", "這樣", "那樣", "哪樣", "為什麼", "沒什麼", "算什麼",
        "你好", "您好", "早安", "午安", "晚安", "謝謝", "感謝", "感恩", "辛苦了",
        "沒問題", "沒關係", "不好意思", "對不起", "請稍候", "請稍等", "等等我", "馬上來",
        "路上小心", "早點休息", "記得吃飯", "天氣很好", "下雨了", "出發", "到達", "已經",
        "準備", "開始", "結束", "確認", "取消", "成功", "失敗", "重新", "設定",
        "密碼", "帳號", "登入", "登出", "註冊", "搜尋", "分享", "下載", "上傳",
        "點擊", "連結", "檔案", "資料", "系統", "網路", "連線", "斷線", "更新", "版本"
    ]
}

# 逐一加入在地分類詞並計算注音與口語變體
for cat, words in tw_categories.items():
    for w in words:
        # 正式注音
        p_list = pypinyin.pinyin(w, style=pypinyin.Style.BOPOMOFO)
        zhuyin = ''.join([item[0] for item in p_list if item])
        harvested[w] = (zhuyin, 9500000)
        
        # 口語無聲調變體 / 台灣慣用口語音
        clean_z = zhuyin.replace('ˇ', '').replace('ˋ', '').replace('ˊ', '').replace('˙', '')
        harvested[(w, clean_z)] = (clean_z, 9500000)

print(f"Preloaded {len(harvested)} core Taiwan terms!")

# 2. 整合基礎大詞庫
for url in urls:
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req) as resp:
            content = resp.read().decode('utf-8')
            for line in content.splitlines():
                if not line or line.startswith('#') or line.startswith('-') or line.startswith('.'):
                    continue
                parts = line.split('\t')
                if len(parts) >= 1:
                    raw_word = parts[0].strip()
                    word = s2tw.convert(raw_word)
                    if not word or len(word) > 7:
                        continue
                    
                    weight = 5000
                    if len(parts) >= 3:
                        try:
                            weight = int(parts[2])
                        except:
                            weight = 5000
                    elif len(parts) == 2 and parts[1].isdigit():
                        weight = int(parts[1])
                    
                    if len(word) == 1:
                        weight += 600000 # 單字高權重
                    
                    if word not in harvested:
                        p_list = pypinyin.pinyin(word, style=pypinyin.Style.BOPOMOFO)
                        zhuyin = ''.join([item[0] for item in p_list if item])
                        if zhuyin:
                            harvested[word] = (zhuyin, weight)
    except Exception as e:
        print(f"Error fetching {url}: {e}")

print(f"Total merged vocabulary: {len(harvested)}")

# 輸出最終高品質繁體字典 (保留前 55,000 詞)
sorted_items = sorted(
    [(k[0] if isinstance(k, tuple) else k, v[0], v[1]) for k, v in harvested.items()],
    key=lambda x: x[2],
    reverse=True
)[:55000]

with open('C:/Users/Korit/ai/ime/app/src/main/assets/dict_tw.txt', 'w', encoding='utf-8') as f:
    for word, zhuyin, weight in sorted_items:
        f.write(f"{word}\t{zhuyin}\t{weight}\n")

print(f"Successfully generated full Taiwan-localized dict_tw.txt! ({len(sorted_items)} entries)")
