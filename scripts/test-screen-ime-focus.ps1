param([string]$OutputDirectory = 'build/screen-ime-focus-validation')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root $OutputDirectory
$classes = Join-Path $out 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
# Host-only framework fixtures; no Android service is started, no permission/focus is fabricated
# on a device. Compile the actual target/session against these controlled system-boundary doubles.
$fixtures = @{
    'android/os/Looper.java' = 'package android.os; public final class Looper { private static final Looper MAIN=new Looper(); public static Looper myLooper(){return MAIN;} public static Looper getMainLooper(){return MAIN;} }'
    'android/os/SystemClock.java' = 'package android.os; public final class SystemClock { public static long uptimeMillis(){return 100;} }'
    'android/view/KeyEvent.java' = 'package android.view; public final class KeyEvent { public static final int ACTION_DOWN=0,ACTION_UP=1; public final int action,keyCode; public KeyEvent(long d,long t,int a,int k,int r){action=a;keyCode=k;} }'
    'android/view/inputmethod/EditorInfo.java' = 'package android.view.inputmethod; public final class EditorInfo {}'
    'android/view/inputmethod/InputBinding.java' = 'package android.view.inputmethod; public final class InputBinding { private final int uid; public InputBinding(int u){uid=u;} public int getUid(){return uid;} }'
    'android/view/inputmethod/InputConnection.java' = 'package android.view.inputmethod; import android.view.KeyEvent; public interface InputConnection { boolean commitText(CharSequence text,int position); boolean sendKeyEvent(KeyEvent event); }'
    'android/inputmethodservice/InputMethodService.java' = 'package android.inputmethodservice; import android.view.inputmethod.*; public final class InputMethodService { public InputConnection connection; public EditorInfo editor; public InputBinding binding; public boolean started=true; public final InputConnection getCurrentInputConnection(){return connection;} public final EditorInfo getCurrentInputEditorInfo(){return editor;} public final InputBinding getCurrentInputBinding(){return binding;} public final boolean getCurrentInputStarted(){return started;} }'
}
$sources = @()
foreach ($item in $fixtures.GetEnumerator()) {
    $file = Join-Path (Join-Path $out 'fixtures') $item.Key
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $file) | Out-Null
    [IO.File]::WriteAllText($file, $item.Value, [Text.UTF8Encoding]::new($false))
    $sources += $file
}
$sourceDir = Join-Path $root 'android/module/src/main/java/io/github/yuejie/connect'
$sources += Join-Path $sourceDir 'RemoteInputSession.java'
$sources += Join-Path $sourceDir 'RemoteInputImeTarget.java'
$sources += Join-Path $root 'android/module/src/test/screen-ime/RemoteInputImeTargetCheck.java'
$java = 'C:\Program Files\Android\Android Studio\jbr\bin'
& (Join-Path $java 'javac.exe') --release 17 -encoding UTF-8 -d $classes @sources 2>&1 | Tee-Object -FilePath (Join-Path $out 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'IME focus fixture compile failed' }
& (Join-Path $java 'java.exe') -cp $classes io.github.yuejie.connect.RemoteInputImeTargetCheck 2>&1 | Tee-Object -FilePath (Join-Path $out 'tests.log')
if ($LASTEXITCODE -ne 0) { throw 'IME focus fixture tests failed' }
@($sources + $PSCommandPath) | ForEach-Object { Get-FileHash -LiteralPath $_ -Algorithm SHA256 } | Select-Object Path,Hash | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'source-sha256.json') -Encoding utf8
