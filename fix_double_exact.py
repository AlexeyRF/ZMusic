with open('app/src/main/java/ru/alexeyrf/maxmusic/MainActivity.kt', 'r', encoding='utf-8') as f:
    text = f.read()

replacements = [
    ('РћС€РёР±РєР° СЃРєР°С‡РёРІР°РЅРёя', 'Ошибка скачивания'),
    ('РћС€РёР±РєР° Р·Р°РіСЂСѓР·РєРё С‚СЂРµРєР°', 'Ошибка загрузки трека'),
    ('РќР°С‡Р°Р»Рѕсь РІРѕСЃРїСЂРѕРёР·РІРµРґРµРЅРёРµ', 'Началось воспроизведение'),
    ('РћС€РёР±РєР° MediaPlayer', 'Ошибка MediaPlayer'),
    ('РЎР±РѕР№ РёРЅРёС†РёР°Р»РёР·Р°С†РёРё РїР»РµРµСЂР°', 'Сбой инициализации плеера'),
    ('РћС€РёР±РєР° СЌС„С„РµРєС‚Р°', 'Ошибка эффекта'),
    ('РћР±С‹С‡РЅС‹Р№', 'Обычный')
]

for old_s, new_s in replacements:
    text = text.replace(old_s, new_s)
    
with open('app/src/main/java/ru/alexeyrf/maxmusic/MainActivity.kt', 'w', encoding='utf-8') as f:
    f.write(text)

print("Fixed double utf-8")
