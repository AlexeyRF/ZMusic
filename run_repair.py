import os

with open('repair.py', 'r', encoding='utf-8') as f:
    text = f.read()

replacements = {
    'Logger.log("��шибка сети: \\")': 'Logger.log("Ошибка сети: ${e.message}")',
    'Logger.log("Ошибка проверки кода: \\")': 'Logger.log("Ошибка проверки кода: ${e.message}")',
    'Logger.log("��шибка проверки паролэ: \\")': 'Logger.log("Ошибка проверки пароля: ${e.message}")',
    'Logger.log("Успешно загружен трек \\")': 'Logger.log("Успешно загружен трек: $fileName")',
    'Logger.log("��шибка загрузки: \\")': 'Logger.log("��шибка загрузки: ${e.message}")',
    'Logger.log("Сбой при загрузке: \\")': 'Logger.log("Сбой при загрузке: ${e.message}")',
    'Text(text = "ID: \\", style': 'Text(text = "ID: ${track.id}", style'
 }

for k, v in replacements.items():
    text = text.replace(k, v)

# Now we write the repaired repair.py but also fix its file writer to use utf-8
# The original repair.py had: with open(filepath, 'w', encoding='utf-8') as f:
# We should ensure that when it runs, IT writes proper UTF-8

# We execute the code of repair.py directly here!
locals_dict = {}
exec(text, globals(), locals_dict)
print("Executed repaired repair.py")
