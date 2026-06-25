with open('app/src/main/java/ru/alexeyrf/maxmusic/MainActivity.kt', 'r', encoding='utf-8') as f: text = f.read()
import re
print("Matches of Cyrillic D0 9E etc:", len(re.findall(r'[\u0400-\u04FF]', text)))
