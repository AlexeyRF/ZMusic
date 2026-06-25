import os

files = [
    'app/src/main/java/ru/alexeyrf/maxmusic/MainActivity.kt'
]

for path in files:
    with open(path, 'r', encoding='utf-8') as f:
        text = f.read()
    
    # Try to fix "Ошибка" type of corruption (double utf-8)
    try:
        # text is currently string. We encode to cp1251 (getting original utf-8 bytes) and decode to utf-8
        fixed_text = text.encode('cp1251').decode('utf-8')
        print(path, "fixed using cp1251->utf-8")
        with open(path, 'w', encoding='utf-8') as f:
            f.write(fixed_text)
    except Exception as e:
        print(path, "failed:", e)
