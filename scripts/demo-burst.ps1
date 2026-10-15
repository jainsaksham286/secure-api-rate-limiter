[CmdletBinding()]
param(
    [Parameter()]
    [string]$BaseUrl = 'http://localhost:8080',

    [Parameter()]
    [string]$ApiKey = 'demo-key-free-123',

    [Parameter()]
    [ValidateRange(1, 500)]
    [int]$Count = 20
)

$endpoint = "$($BaseUrl.TrimEnd('/'))/api/demo/data"
$results = for ($attempt = 1; $attempt -le $Count; $attempt++) {
    try {
        $response = Invoke-WebRequest -Uri $endpoint -Headers @{ 'X-API-Key' = $ApiKey } -UseBasicParsing -TimeoutSec 10
        [PSCustomObject]@{
            Attempt = $attempt
            Status = [int]$response.StatusCode
            Remaining = $response.Headers['X-RateLimit-Remaining']
            RetryAfter = $response.Headers['Retry-After']
        }
    }
    catch {
        $httpResponse = $_.Exception.Response
        if ($null -eq $httpResponse) {
            [PSCustomObject]@{
                Attempt = $attempt
                Status = 0
                Remaining = '-'
                RetryAfter = '-'
            }
        }
        else {
            [PSCustomObject]@{
                Attempt = $attempt
                Status = [int]$httpResponse.StatusCode
                Remaining = $httpResponse.Headers['X-RateLimit-Remaining']
                RetryAfter = $httpResponse.Headers['Retry-After']
            }
        }
    }
}

$results | Format-Table -AutoSize