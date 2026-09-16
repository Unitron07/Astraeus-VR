param([string]$Compiler = 'g++')
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    New-Item -ItemType Directory -Force build | Out-Null
    & $Compiler -std=c++17 -O2 -Wall -Wextra -Wpedantic -DWIN32_LEAN_AND_MEAN -DNOMINMAX -Iinclude src/main.cpp src/receiver.cpp src/visualization.cpp -o build/AstraeusPoseViewer.exe -mwindows -static -lws2_32 -lgdi32 -luser32
    if ($LASTEXITCODE -ne 0) { throw 'Viewer build failed' }
    & $Compiler -std=c++17 -O2 -Wall -Wextra -Wpedantic -Iinclude tests/protocol_tests.cpp -o build/protocol_tests.exe -static
    if ($LASTEXITCODE -ne 0) { throw 'Test build failed' }
    & ./build/protocol_tests.exe ../../protocol/golden_pose.hex
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
    & $Compiler -std=c++17 -O2 -Wall -Wextra -Wpedantic -DWIN32_LEAN_AND_MEAN -DNOMINMAX -Iinclude tests/receiver_tests.cpp src/receiver.cpp -o build/receiver_tests.exe -static -lws2_32
    if ($LASTEXITCODE -ne 0) { throw 'Receiver test build failed' }
    & ./build/receiver_tests.exe ../../protocol/golden_pose.hex
    if ($LASTEXITCODE -ne 0) { throw 'Receiver tests failed' }
} finally { Pop-Location }
