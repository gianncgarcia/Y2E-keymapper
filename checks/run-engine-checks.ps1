$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$classesDir = Join-Path ([System.IO.Path]::GetTempPath()) ('y2e-engine-checks-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classesDir | Out-Null
$engineDir = Join-Path $projectDir 'app/src/main/java/com/codex/keymapper/engine'
& javac -encoding UTF-8 -d $classesDir (Join-Path $engineDir 'PointerEngine.java') (Join-Path $engineDir 'DpadHandler.java') (Join-Path $PSScriptRoot 'ReviewCheck.java') (Join-Path $PSScriptRoot 'TransitionDelayCheck.java') (Join-Path $PSScriptRoot 'NoOvershootCheck.java') (Join-Path $PSScriptRoot 'MovementTuningCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Engine check compilation failed' }
& java -cp $classesDir com.codex.keymapper.engine.ReviewCheck
if ($LASTEXITCODE -ne 0) { throw 'Engine behavior checks failed' }

& java -cp $classesDir com.codex.keymapper.engine.TransitionDelayCheck
if ($LASTEXITCODE -ne 0) { throw 'Direction delay checks failed' }

& java -cp $classesDir com.codex.keymapper.engine.NoOvershootCheck
if ($LASTEXITCODE -ne 0) { throw 'Joystick overshoot checks failed' }

& java -cp $classesDir com.codex.keymapper.engine.MovementTuningCheck
if ($LASTEXITCODE -ne 0) { throw 'Movement tuning checks failed' }

