with open('app/src/main/java/ru/alexeyrf/maxmusic/MainActivity.kt', 'r', encoding='utf-8') as f: text1 = f.read()
with open('app/src/main/java/ru/alexeyrf/maxmusic/client/MaxClient.kt', 'r', encoding='utf-8') as f: text2 = f.read()

print("Main has UFFFD:", '\ufffd' in text1)
print("Client has UFFFD:", '\ufffd' in text2)
print("Main has double-utf8 (Ош):", 'Ош' in text1)
