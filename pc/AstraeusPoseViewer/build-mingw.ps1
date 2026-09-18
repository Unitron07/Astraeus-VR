param([string]$Compiler = 'g++', [string]$OutputDirectory = 'build')
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
    $outputPath = (Resolve-Path $OutputDirectory).Path
    & $Compiler -std=c++17 -O2 -Wall -Wextra -Wpedantic -DWIN32_LEAN_AND_MEAN -DNOMINMAX -Iinclude src/main.cpp src/receiver.cpp src/visualization.cpp -o "$outputPath/AstraeusPoseViewer.exe" -mwindows -static -lws2_32 -lgdi32 -luser32
    if ($LASTEXITCODE -ne 0) { throw 'Viewer build failed' }
    & $Compiler -std=c++17 -O2 -Wall -Wextra -Wpedantic -Iinclude tests/protocol_tests.cpp -o "$outputPath/protocol_tests.exe" -static
    if ($LASTEXITCODE -ne 0) { throw 'Test build failed' }
    & "$outputPath/protocol_tests.exe" ../../protocol/golden_pose.hex
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
    & $Compiler -std=c++17 -O2 -Wall -Wextra -Wpedantic -DWIN32_LEAN_AND_MEAN -DNOMINMAX -Iinclude tests/receiver_tests.cpp src/receiver.cpp -o "$outputPath/receiver_tests.exe" -static -lws2_32
    if ($LASTEXITCODE -ne 0) { throw 'Receiver test build failed' }
    & "$outputPath/receiver_tests.exe" ../../protocol/golden_pose.hex
    if ($LASTEXITCODE -ne 0) { throw 'Receiver tests failed' }
    & $Compiler -std=c++17 -O2 -Wall -Wextra -Wpedantic -Iinclude tests/fusion_protocol_tests.cpp -o "$outputPath/fusion_protocol_tests.exe" -static
    if ($LASTEXITCODE -ne 0) { throw 'Fusion protocol build failed' }
    & "$outputPath/fusion_protocol_tests.exe" ../../protocol
    if ($LASTEXITCODE -ne 0) { throw 'Fusion protocol tests failed' }
} finally { Pop-Location }
