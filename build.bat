@echo off
setlocal

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "TARGET=%ROOT%\target"

for /f "tokens=1,* delims==" %%A in ('findstr /B "mod_version=" "%ROOT%\gradle.properties"') do (
    set "VERSION=%%B"
)
for /f "tokens=1,* delims==" %%A in ('findstr /B "neoforge_1211_mod_version=" "%ROOT%\gradle.properties"') do (
    set "NEOFORGE_1211_VERSION=%%B"
)

if "%VERSION%"=="" (
    echo Could not read mod_version from gradle.properties.
    exit /b 1
)
if "%NEOFORGE_1211_VERSION%"=="" (
    echo Could not read neoforge_1211_mod_version from gradle.properties.
    exit /b 1
)

if not exist "%TARGET%" (
    mkdir "%TARGET%"
    if errorlevel 1 (
        echo Could not create target directory: %TARGET%
        exit /b 1
    )
)

call :buildNeoForgeVariant 1.21.1 21 13.0.11 21.1.223 4.0.42 "https://piston-data.mojang.com/v1/objects/30c73b1c5da787909b2f73340419fdf13b9def88/client.jar"
if errorlevel 1 exit /b 1

call :buildVariant 1.21.11 21 1.21.11-R0.1-SNAPSHOT 1.21 19.0.1 0.141.4+1.21.11 21.11.42 4.0.42 true false ""
if errorlevel 1 exit /b 1

call :buildVariant 26.1 25 26.1-R0.1-SNAPSHOT 26.1 20.0.4 0.145.1+26.1 26.1.0.19-beta 4.0.42 false true "https://piston-data.mojang.com/v1/objects/191771837687b766537a8c4607cb6fad79c533a1/client.jar"
if errorlevel 1 exit /b 1

echo Built all ZstdNet variants into: %TARGET%
endlocal
exit /b 0

:buildNeoForgeVariant
set "MC_VERSION=%~1"
set "JAVA_VERSION=%~2"
set "ARCHITECTURY_API_VERSION=%~3"
set "NEOFORGE_VERSION=%~4"
set "NEOFORGE_FML_LOADER_VERSION=%~5"
set "MINECRAFT_CLIENT_URL=%~6"

echo.
echo Building ZstdNet NeoForge server-client mod for Minecraft %MC_VERSION%...

call "%ROOT%\gradlew.bat" :neoforge:clean :neoforge:build ^
    -Pminecraft_version=%MC_VERSION% ^
    -Pjava_version=%JAVA_VERSION% ^
    -Parchitectury_api_version=%ARCHITECTURY_API_VERSION% ^
    -Pneoforge_version=%NEOFORGE_VERSION% ^
    -Pneoforge_fml_loader_version=%NEOFORGE_FML_LOADER_VERSION% ^
    -Pneoforge_variant=server-1211 ^
    -Pclient_loom_enabled=false ^
    -Pnamed_client_jar_enabled=false
if errorlevel 1 (
    echo NeoForge server-client build failed for Minecraft %MC_VERSION%.
    exit /b 1
)

set "NEOFORGE_JAR=%ROOT%\neoforge\build\libs\neoforge-%NEOFORGE_1211_VERSION%.jar"
set "OUTPUT_NEOFORGE_JAR=%TARGET%\ZstdNet-%MC_VERSION%-neoforge-server-client-%NEOFORGE_1211_VERSION%.jar"

if not exist "%NEOFORGE_JAR%" (
    echo Expected NeoForge jar was not found: %NEOFORGE_JAR%
    exit /b 1
)

copy /Y "%NEOFORGE_JAR%" "%OUTPUT_NEOFORGE_JAR%" >nul
if errorlevel 1 (
    echo Could not copy NeoForge server-client jar to target.
    exit /b 1
)

echo Built NeoForge server-client mod: %OUTPUT_NEOFORGE_JAR%
exit /b 0

:buildVariant
set "MC_VERSION=%~1"
set "JAVA_VERSION=%~2"
set "SPIGOT_API_VERSION=%~3"
set "PLUGIN_API_VERSION=%~4"
set "ARCHITECTURY_API_VERSION=%~5"
set "FABRIC_API_VERSION=%~6"
set "NEOFORGE_VERSION=%~7"
set "NEOFORGE_FML_LOADER_VERSION=%~8"
set "CLIENT_LOOM_ENABLED=%~9"
shift
set "NAMED_CLIENT_JAR_ENABLED=%~9"
shift
set "MINECRAFT_CLIENT_URL=%~9"

echo.
echo Building ZstdNet for Minecraft %MC_VERSION%...

call "%ROOT%\gradlew.bat" clean build ^
    -Pminecraft_version=%MC_VERSION% ^
    -Pjava_version=%JAVA_VERSION% ^
    -Pspigot_api_version=%SPIGOT_API_VERSION% ^
    -Pplugin_api_version=%PLUGIN_API_VERSION% ^
    -Parchitectury_api_version=%ARCHITECTURY_API_VERSION% ^
    -Pfabric_api_version=%FABRIC_API_VERSION% ^
    -Pneoforge_version=%NEOFORGE_VERSION% ^
    -Pneoforge_fml_loader_version=%NEOFORGE_FML_LOADER_VERSION% ^
    -Pclient_loom_enabled=%CLIENT_LOOM_ENABLED% ^
    -Pneoforge_variant=client ^
    -Pnamed_client_jar_enabled=%NAMED_CLIENT_JAR_ENABLED% ^
    -Pminecraft_client_url=%MINECRAFT_CLIENT_URL%
if errorlevel 1 (
    echo Build failed for Minecraft %MC_VERSION%.
    exit /b 1
)

set "PLUGIN_JAR=%ROOT%\spigot\build\libs\spigot-%VERSION%.jar"
set "FABRIC_JAR=%ROOT%\fabric\build\libs\fabric-%VERSION%.jar"
set "NEOFORGE_JAR=%ROOT%\neoforge\build\libs\neoforge-%VERSION%.jar"
set "OUTPUT_JAR=%TARGET%\ZstdNet-%MC_VERSION%-spigot-%VERSION%.jar"
set "OUTPUT_FABRIC_JAR=%TARGET%\ZstdNet-%MC_VERSION%-fabric-%VERSION%.jar"
set "OUTPUT_NEOFORGE_JAR=%TARGET%\ZstdNet-%MC_VERSION%-neoforge-%VERSION%.jar"

if not exist "%PLUGIN_JAR%" (
    echo Expected plugin jar was not found: %PLUGIN_JAR%
    exit /b 1
)

if not exist "%FABRIC_JAR%" (
    echo Expected Fabric client jar was not found: %FABRIC_JAR%
    exit /b 1
)

if not exist "%NEOFORGE_JAR%" (
    echo Expected NeoForge client jar was not found: %NEOFORGE_JAR%
    exit /b 1
)

copy /Y "%PLUGIN_JAR%" "%OUTPUT_JAR%" >nul
if errorlevel 1 (
    echo Could not copy plugin jar to target.
    exit /b 1
)

copy /Y "%FABRIC_JAR%" "%OUTPUT_FABRIC_JAR%" >nul
if errorlevel 1 (
    echo Could not copy Fabric client jar to target.
    exit /b 1
)

copy /Y "%NEOFORGE_JAR%" "%OUTPUT_NEOFORGE_JAR%" >nul
if errorlevel 1 (
    echo Could not copy NeoForge client jar to target.
    exit /b 1
)

echo Built plugin: %OUTPUT_JAR%
echo Built Fabric client: %OUTPUT_FABRIC_JAR%
echo Built NeoForge client: %OUTPUT_NEOFORGE_JAR%
exit /b 0
