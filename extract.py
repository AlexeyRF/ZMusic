import re

with open('app/src/main/java/ru/alexeyrf/maxmusic/MainActivity.kt', 'r', encoding='utf-8') as f:
    text = f.read()

strings = re.findall(r'\"([^\"]*?)\"', text)
for s in set(strings):
    if any(ord(c) > 127 for c in s):
        print(s)
