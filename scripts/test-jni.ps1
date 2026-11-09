param([string]$Jdk = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath (Join-Path $Jdk 'bin\javac.exe'))) { throw 'JAVA_HOME must be a JDK with javac' }
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    cargo build --locked -p yj-jni
    if ($LASTEXITCODE -ne 0) { throw 'Host JNI build failed' }
    New-Item -ItemType Directory -Force build/jni-test | Out-Null
    & (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -d build/jni-test android/module/src/main/java/io/github/yuejie/connect/NativeCore.java native/tests/JniSmoke.java
    if ($LASTEXITCODE -ne 0) { throw 'JNI smoke compilation failed' }
    & (Join-Path $Jdk 'bin\java.exe') --enable-native-access=ALL-UNNAMED "-Djava.library.path=$root\target\debug" -cp build/jni-test io.github.yuejie.connect.JniSmoke
    if ($LASTEXITCODE -ne 0) { throw 'JNI smoke failed' }
} finally { Pop-Location }
