Файлы `module.zip` и `module.prop` генерируются корневым `build.sh`.

Обычный сценарий:
- `./build.sh module` — собрать ZIP модуля и встроить его в assets
- `./build.sh apk` — собрать ZIP модуля и APK

Если собрать только Gradle-проект напрямую, встроенного ZIP может не быть.
