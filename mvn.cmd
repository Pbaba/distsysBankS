@echo off
set "MAVEN_BIN=C:\Users\byaha_gv5s830\AppData\Local\Programs\Maven\apache-maven-3.9.6\bin\mvn.cmd"
if exist "%MAVEN_BIN%" (
    "%MAVEN_BIN%" %*
) else (
    mvn %*
)
