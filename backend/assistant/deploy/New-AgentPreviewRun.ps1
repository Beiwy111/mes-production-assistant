[CmdletBinding()]
param([Parameter(Mandatory=$true)][ValidatePattern('^[1-9][0-9]*$')][string]$OrderPk,
      [string]$TargetDate,
      [ValidateSet('FACTORY_SHIPMENT','CUSTOMER_RECEIPT')][string]$TargetKind = 'FACTORY_SHIPMENT',
      [ValidateSet('DELIVERY','ORDER','SUPPLY','PRODUCTION','QUALITY','EQUIPMENT','AFTERSALES','FULL_ASSESSMENT')][string]$Profile = 'DELIVERY')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'AgentRuntime.Common.ps1')
$config = Read-AgentRuntimeConfig
if ($OrderPk -notin $config['MES_AGENT_ALLOWED_ORDER_IDS'].Split(',')) { throw 'Order is not in the explicit local preview allowlist.' }
$port = 18082
if ($config.ContainsKey('MES_AGENT_PORT')) { $port = [int]$config['MES_AGENT_PORT'] }
$body = @{ orderPk = $OrderPk; targetKind = $TargetKind; workflowProfile = $Profile }
if ($TargetDate) { $body.targetDate = $TargetDate }
$result = Invoke-RestMethod -Uri ('http://127.0.0.1:' + $port + '/stage5/runs') -Method Post `
    -Headers @{ Authorization = 'Bearer ' + $config['MES_AGENT_BOOTSTRAP_KEY'] } -ContentType 'application/json' -Body ($body | ConvertTo-Json -Compress)
$path = Join-Path $PSScriptRoot '..\rag\data\stage5-latest-run.json'
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $path -Encoding utf8
Write-Host ('Created 10-minute read-only preview run for order ' + $OrderPk + '. Paste run_ref into the supervisor workflow:')
Write-Output $result.runRef
