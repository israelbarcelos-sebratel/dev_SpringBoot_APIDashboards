# Mantém o Windows acordado enquanto o ajudante (container historico-gpu) estiver rodando: o modo de espera por
# inatividade pausa o Docker/WSL e o ajudante para. Não muda nenhuma configuração de energia — é o mesmo pedido que um
# player de vídeo faz (SetThreadExecutionState) e vale só enquanto este script roda; com o ajudante parado, a máquina
# volta a poder dormir. Fechar a tampa ou apertar o botão de energia ainda põem a máquina para dormir.
#
#   Start-Process powershell -WindowStyle Hidden -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','manter-acordado.ps1'
#   (de dentro de analise-ligacoes\gpu; para parar: feche o processo powershell dele)
param([string]$Container = "historico-gpu", [int]$Intervalo = 60)

Add-Type -Namespace Win32 -Name Energia -MemberDefinition @'
[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);
'@
$CONTINUO = [uint32]"0x80000000"
$SISTEMA = [uint32]"0x00000001"

$acordado = $false
while ($true) {
    $rodando = (docker inspect -f "{{.State.Running}}" $Container 2>$null) -eq "true"
    if ($rodando -and -not $acordado) {
        [void][Win32.Energia]::SetThreadExecutionState($CONTINUO -bor $SISTEMA)
        Write-Host "$(Get-Date -Format HH:mm:ss) $Container rodando: a máquina fica acordada"
        $acordado = $true
    } elseif (-not $rodando -and $acordado) {
        [void][Win32.Energia]::SetThreadExecutionState($CONTINUO)
        Write-Host "$(Get-Date -Format HH:mm:ss) $Container parado: a máquina pode dormir"
        $acordado = $false
    }
    Start-Sleep -Seconds $Intervalo
}
