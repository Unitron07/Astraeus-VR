param([string]$Address = '127.0.0.1', [int]$Port = 4242, [int]$Seconds = 10,
    [ValidateSet('NONE','BAD_STATE','INSUFFICIENT_LIGHT','EXCESSIVE_MOTION','INSUFFICIENT_FEATURES','CAMERA_UNAVAILABLE')]
    [string]$TrackingFailureReason = 'NONE', [switch]$Paused)
$ErrorActionPreference = 'Stop'
if ($Port -lt 1 -or $Port -gt 65535 -or $Seconds -lt 1) { throw 'Invalid port or duration' }
$udp = [Net.Sockets.UdpClient]::new()
$udp.Connect($Address, $Port)
$timer = [Diagnostics.Stopwatch]::StartNew()
$packet = New-Object byte[] 96
[Text.Encoding]::ASCII.GetBytes('ASTR').CopyTo($packet, 0)
$packet[4] = 2; $packet[5] = 1; $packet[6] = 96
[BitConverter]::GetBytes([long][DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()).CopyTo($packet, 16)
$packet[36] = 1; $packet[37] = 1; $packet[38] = 2
$reasons = @('NONE','BAD_STATE','INSUFFICIENT_LIGHT','EXCESSIVE_MOTION','INSUFFICIENT_FEATURES','CAMERA_UNAVAILABLE')
$packet[92] = [byte][Array]::IndexOf($reasons, $TrackingFailureReason.ToUpperInvariant())
if ($Paused -or $packet[92] -ne 0) { $packet[38] = 1 }
[uint32]$sequence = 0
Write-Output 'SYNTHETIC data only. Reset the PC stream before/after this test.'
try {
    while ($timer.Elapsed.TotalSeconds -lt $Seconds) {
        $t = $timer.Elapsed.TotalSeconds
        [BitConverter]::GetBytes($sequence++).CopyTo($packet, 8)
        [BitConverter]::GetBytes([long]($t * 1e9)).CopyTo($packet, 24)
        [BitConverter]::GetBytes([single]([Math]::Sin($t) * 0.5)).CopyTo($packet, 40)
        [BitConverter]::GetBytes([single]([Math]::Sin($t / 2) * 0.25)).CopyTo($packet, 44)
        [BitConverter]::GetBytes([single]([Math]::Sin($t / 2))).CopyTo($packet, 56)
        [BitConverter]::GetBytes([single]([Math]::Cos($t / 2))).CopyTo($packet, 64)
        if ($packet[38] -eq 1) {
            [Array]::Clear($packet, 40, 28)
            [BitConverter]::GetBytes([single]1).CopyTo($packet, 64)
        }
        [void]$udp.Send($packet, $packet.Length)
        Start-Sleep -Milliseconds 16
    }
} finally { $udp.Dispose() }
