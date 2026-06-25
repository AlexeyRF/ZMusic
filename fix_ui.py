import re

with open('app/src/main/java/ru/alexeyrf/maxmusic/MainActivity.kt', 'r', encoding='utf-8') as f:
    text = f.read()

replacements = {
    '\xa0Ў\xa0±\xa0ѕ\xa0№ \xa0ё\xa0Ѕ\xa0ёС\xa0\xa0ё\xa0°\xa0»\xa0ё\xa0·\xa0°С\xa0\xa0ё\xa0ё \xa0ї\xa0»\xa0µ\xa0µСЂ\xa0°: ': 'Сбой инициализации плеера: ',
    '  : ': 'Ошибка проверки пароля: ',
    '  PyMax Music': 'Вход в PyMax Music',
    ' ': 'Настройки',
    ' SMS ...': 'Отправка SMS кода...',
    ' 2FA': 'Пароль 2FA',
    '': 'Войти',
    '  ': 'Логи приложения',
    ' !': 'Успешный вход!',
    '    .': 'Неверный пароль или ошибка входа.',
    ' : ': 'Ошибка: Телефон не принят сервером.',
    '  . ...': 'Найдена сохраненная сессия. Подключение...',
    '\xa0ћС€\xa0ё\xa0±\xa0є\xa0° \xa0·\xa0°\xa0іСЂСѓ\xa0·\xa0є\xa0ё С‚СЂ\xa0µ\xa0є\xa0°: ': 'Ошибка загрузки трека: ',
    '\xa0ћС€\xa0ё\xa0±\xa0є\xa0° MediaPlayer: what= extra=': 'Ошибка MediaPlayer: what= extra=',
    '...': 'Авторизация...',
    '   .  .': 'Не удалось восстановить сессию. Требуется вход.',
    '\xa0ћС€\xa0ё\xa0±\xa0є\xa0° СЃ\xa0є\xa0°С‡\xa0ё\xa0І\xa0°\xa0Ѕ\xa0ёя: ': 'Ошибка скачивания: ',
    ': 1 GB': 'Лимит: 1 GB',
    ' ,  SMS...': 'Телефон принят, ожидание SMS...',
    '\xa0ќ\xa0°С‡\xa0°\xa0»\xa0ѕсь \xa0І\xa0ѕСЃ\xa0їСЂ\xa0ѕ\xa0ё\xa0·\xa0І\xa0µ\xa0ґ\xa0µ\xa0Ѕ\xa0ё\xa0µ: ': 'Началось воспроизведение: ',
    '   .  ...': 'Запуск подключения...',
    ':    .': 'Ошибка: Телефон не принят сервером.',
    '\xa0ћС€\xa0ё\xa0±\xa0є\xa0° СЃ\xa0є\xa0°С‡\xa0ё\xa0І\xa0°\xa0Ѕ\xa0ёя: HTTP ': 'Ошибка скачивания: HTTP ',
    ' SMS ': 'Введите SMS код',
    ' ...': 'Авторизация...',
    ': 100 MB': 'Лимит: 100 MB',
    '\xa0ћ\xa0±С‹С‡\xa0ЅС‹\xa0№': 'Обычный',
    '  2FA.': 'Требуется пароль 2FA.',
    '  2FA...': 'Отправка пароля 2FA...',
    'SMS ': 'SMS код',
    '  : ': 'Сбой при загрузке: ',
    ' ()': 'Музыка (Избранное)',
    '\xa0ћС€\xa0ё\xa0±\xa0є\xa0° СЌС„С„\xa0µ\xa0єС‚\xa0°: ': 'Ошибка эффекта: ',
    ': 500 MB': 'Лимит: 500 MB',
    '   ': 'Успешно загружен трек ',
    ' ': 'Отправить',
    '  ': 'Логи приложения',
    '': 'Назад',
    ' !': 'Сессия восстановлена!',
    ' : ': 'Ошибка проверки кода: '
}

# Apply replacements
for old_s, new_s in replacements.items():
    text = text.replace('"' + old_s + '"', '"' + new_s + '"')
    
# There are a few that we missed:
text = text.replace('" : \\"', '"Ошибка сети: "')
text = text.replace('"  : \\"', '"Сбой при загрузке: "')
text = text.replace('" : \\"', '"Ошибка проверки кода: "')
text = text.replace('" : \\"', '"Ошибка проверки пароля: "')
text = text.replace('"   \\"', '"Успешно загружен трек "')
text = text.replace('":    ."', '"Ошибка: Телефон не принят сервером."')
text = text.replace('""', '"Отправить"')
text = text.replace('""', '"Войти"')
text = text.replace('""', '"Назад"')
text = text.replace('" "', '"Номер телефона"')
text = text.replace('" "', '"Получить код"')
text = text.replace('" "', '"Добавить музыку"')
text = text.replace('"  "', '"Нет загруженной музыки"')
text = text.replace('" : "', '"Ошибка загрузки: "')
text = text.replace('" : "', '"Только кэш: "')
text = text.replace('""', '"Вкл"')
text = text.replace('""', '"Выкл"')
text = text.replace('" 2FA"', '"Двухфакторная аутентификация"')

with open('app/src/main/java/ru/alexeyrf/maxmusic/MainActivity.kt', 'w', encoding='utf-8') as f:
    f.write(text)

print("Applied UI fixes to MainActivity.kt")
