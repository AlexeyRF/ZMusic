import re

with open('app/src/main/java/ru/alexeyrf/maxmusic/MainActivity.kt', 'r', encoding='utf-8') as f:
    text = f.read()

strings = re.findall(r'\"([^\"]*?(?:[\u0400-\u04FF]|\ufffd)[^\"]*?)\"', text)
for s in set(strings):
    print(repr(s))
