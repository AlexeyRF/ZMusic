with open('app/src/main/java/ru/alexeyrf/maxmusic/client/MaxClient.kt', 'r', encoding='utf-8') as f:
    text = f.read()

replacements = [
    ('throw Exception("    token: ")', 'throw Exception("Сервер не принял login token: ")'),
    ('throw Exception("  authToken")', 'throw Exception("Нет authToken")'),
    ('throw Exception("   login token: ")', 'throw Exception("Ошибка входа по login token: ")'),
    ('throw Exception("   info  FILE_UPLOAD: ")', 'throw Exception("Сервер не вернул info для FILE_UPLOAD: ")'),
    ('throw Exception(" infoList")', 'throw Exception("Пустой infoList")'),
    ('throw Exception(" url  info: ")', 'throw Exception("Нет url в info: ")'),
    ('throw Exception(" fileId  info: ")', 'throw Exception("Нет fileId в info: ")'),
    ('throw Exception(" MSG_SEND (64): ")', 'throw Exception("Ошибка MSG_SEND (64): ")')
]

for old_s, new_s in replacements:
    text = text.replace(old_s, new_s)
    
with open('app/src/main/java/ru/alexeyrf/maxmusic/client/MaxClient.kt', 'w', encoding='utf-8') as f:
    f.write(text)

print("Fixed MaxClient exceptions")
