@ECHO OFF
SET APP_HOME=%~dp0
IF EXIST "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" (
  java -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
  EXIT /B %ERRORLEVEL%
)
where gradle >NUL 2>NUL
IF %ERRORLEVEL% EQU 0 (
  gradle %*
  EXIT /B %ERRORLEVEL%
)
ECHO gradle-wrapper.jar not found and system gradle is unavailable.
EXIT /B 1
