# Painel do mês em processamento + Windows acordado, numa janela só: enquanto ela estiver aberta o Windows não entra
# em espera por inatividade (o modo de espera pausa o Docker e o ajudante para). Fechou a janela, a máquina volta a
# poder dormir. Não muda nenhuma configuração de energia (é o pedido que um player de vídeo faz). Fechar a tampa ou o
# botão de energia ainda põem a máquina para dormir.
#
#   Start-Process powershell -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','painel.ps1'
#   (de dentro de analise-ligacoes\gpu)
param([string]$Container = "historico-gpu")

Add-Type -Namespace Win32 -Name Energia -MemberDefinition @'
[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);
'@
# ES_CONTINUOUS | ES_SYSTEM_REQUIRED: vale para esta janela até ela fechar
[void][Win32.Energia]::SetThreadExecutionState([uint32]"0x80000001")
$Host.UI.RawUI.WindowTitle = "Histórico - painel (mantendo o Windows acordado)"

while ($true) {
    docker exec -it $Container python -m app.painel
    # o painel saiu (container reiniciando, Docker voltando): tenta de novo, sem soltar o "acordado"
    Write-Host "painel parou; tentando de novo em 15 s (feche a janela para encerrar)"
    Start-Sleep -Seconds 15
}
