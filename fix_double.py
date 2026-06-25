import re

def fix_double_utf8(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        text = f.read()
    
    def replacer(match):
        s = match.group(0)
        try:
            return s.encode('cp1251').decode('utf-8')
        except:
            return s
            
    fixed_text = re.sub(r'[\u0400-\u04FF]+', replacer, text)
    
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(fixed_text)

fix_double_utf8('app/src/main/java/ru/alexeyrf/maxmusic/MainActivity.kt')
print("Fixed double utf-8")
