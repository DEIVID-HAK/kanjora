$ErrorActionPreference = "Stop"

$KeycloakTokenUrl = "http://localhost:8180/realms/kanjora/protocol/openid-connect/token"
$DomainApi = "http://localhost:8080"
$SessionsApi = "http://localhost:8081"

$SkillBackend = "20000000-0000-4000-8000-000000000001"
$LevelBackendInt = "30000000-0000-4000-8000-000000000002"
$TopicRest = "40000000-0000-4000-8000-000000000001"

$SkillEnglish = "20000000-0000-4000-8000-000000000002"
$LevelEnglishB1 = "30000000-0000-4000-8000-000000000012"
$TopicInterviews = "40000000-0000-4000-8000-000000000011"

function Get-UserToken([string]$username, [string]$password) {
    $resp = Invoke-RestMethod -Method Post -Uri $KeycloakTokenUrl -Body @{
        client_id  = "kanjora-web"
        grant_type = "password"
        username   = $username
        password   = $password
    }
    return $resp.access_token
}

function Invoke-Api([string]$method, [string]$uri, [string]$token, $bodyObj = $null, [hashtable]$extraHeaders = @{}) {
    $headers = @{ Authorization = "Bearer $token" }
    foreach ($k in $extraHeaders.Keys) { $headers[$k] = $extraHeaders[$k] }
    if ($null -ne $bodyObj) {
        $json = $bodyObj | ConvertTo-Json -Depth 8
        return Invoke-RestMethod -Method $method -Uri $uri -Headers $headers -ContentType "application/json" -Body $json
    }
    return Invoke-RestMethod -Method $method -Uri $uri -Headers $headers
}

function Assert-Fails([string]$label, [scriptblock]$action) {
    try {
        & $action | Out-Null
        throw "FALLO DE SEGURIDAD/REGLA: Se esperaba rechazo en [$label] pero respondió 2xx."
    } catch {
        if ($_.Exception.Message -like "FALLO DE SEGURIDAD*") { throw }
        Write-Host "   OK (rechazo esperado): $label"
    }
}

Write-Host "1) Obteniendo tokens OIDC desde Keycloak..."
$aliceTok = Get-UserToken "alice" "alice_dev_pwd"
$bobTok = Get-UserToken "bob" "bob_dev_pwd"
$revTok = Get-UserToken "reviewer" "reviewer_dev_pwd"

Write-Host "2) Creando perfiles recíprocos y disponibilidad común (B05)..."
Invoke-Api Put "$DomainApi/api/v1/profiles/me" $aliceTok @{
    displayName       = "Alice Backend"
    bio               = "Ingeniera Java/Go"
    timezone          = "America/Bogota"
    availabilitySlots = @(@{ dayOfWeek = 3; startTimeLocal = "18:00"; endTimeLocal = "20:00" })
    learningGoals     = @(@{ skillId = $SkillEnglish; targetLevelId = $LevelEnglishB1; topicIds = @($TopicInterviews) })
    teachingOffers    = @(@{ skillId = $SkillBackend; levelId = $LevelBackendInt; topicIds = @($TopicRest) })
} | Out-Null

Invoke-Api Put "$DomainApi/api/v1/profiles/me" $bobTok @{
    displayName       = "Bob English"
    bio               = "Technical Communication Coach"
    timezone          = "America/Bogota"
    availabilitySlots = @(@{ dayOfWeek = 3; startTimeLocal = "18:30"; endTimeLocal = "20:30" })
    learningGoals     = @(@{ skillId = $SkillBackend; targetLevelId = $LevelBackendInt; topicIds = @($TopicRest) })
    teachingOffers    = @(@{ skillId = $SkillEnglish; levelId = $LevelEnglishB1; topicIds = @($TopicInterviews) })
} | Out-Null

Write-Host "3) Verificando bloqueo de matching sin habilitación aprobada (B07)..."
Assert-Fails "Matching sin habilitación aprobada" {
    Invoke-Api Get "$DomainApi/api/v1/matches" $aliceTok
}

Write-Host "4) Habilitación en 3 pasos y revisión humana (B06-B07)..."
$qualAlice = Invoke-Api Post "$DomainApi/api/v1/teaching-qualifications" $aliceTok @{
    skillId = $SkillBackend; levelId = $LevelBackendInt
    methodologyAcknowledged = $true; practicalTestPassed = $true; practicalTestReference = "test-java-01"
}
$subAlice = Invoke-Api Post "$DomainApi/api/v1/teaching-qualifications/$($qualAlice.qualificationId)/mini-class" $aliceTok @{
    lessonObjective = "Explicar diseño de contratos REST idempotentes"
    recordingOrArtifactUrl = "https://example.com/miniclass-alice"
    selfCheckSummary = "Incluye objetivo, ejemplo, práctica y verificación final."
}

$qualBob = Invoke-Api Post "$DomainApi/api/v1/teaching-qualifications" $bobTok @{
    skillId = $SkillEnglish; levelId = $LevelEnglishB1
    methodologyAcknowledged = $true; practicalTestPassed = $true; practicalTestReference = "test-eng-01"
}
$subBob = Invoke-Api Post "$DomainApi/api/v1/teaching-qualifications/$($qualBob.qualificationId)/mini-class" $bobTok @{
    lessonObjective = "Practicar respuestas de trade-offs en entrevistas técnicas"
    recordingOrArtifactUrl = "https://example.com/miniclass-bob"
    selfCheckSummary = "Estructura de 4 pasos aplicada a entrevista."
}

Assert-Fails "Usuario estándar intentando aprobar mini clase" {
    Invoke-Api Post "$DomainApi/api/v1/admin/mini-class-submissions/$($subAlice.latestSubmissionId)/review" $aliceTok @{
        decision = "APPROVE"; reviewerComments = "Intento no autorizado"
    }
}

Invoke-Api Post "$DomainApi/api/v1/admin/mini-class-submissions/$($subAlice.latestSubmissionId)/review" $revTok @{
    decision = "APPROVE"; reviewerComments = "Metodología de 4 pasos aplicada con claridad."
} | Out-Null

Invoke-Api Post "$DomainApi/api/v1/admin/mini-class-submissions/$($subBob.latestSubmissionId)/review" $revTok @{
    decision = "APPROVE"; reviewerComments = "Excelente práctica guiada para entrevista técnica."
} | Out-Null

Write-Host "5) Buscando match recíproco explicable (B08)..."
$matches = Invoke-Api Get "$DomainApi/api/v1/matches" $aliceTok
if (-not $matches.hasValidMatches -or $matches.candidates.Count -lt 1) {
    throw "Se esperaba al menos 1 match recíproco válido."
}
$bobCandidate = $matches.candidates[0]
Write-Host "   Match encontrado: $($bobCandidate.displayName) | $($bobCandidate.explanation.summaryText)"

Write-Host "6) Creando y aceptando propuesta de intercambio (B09)..."
$proposal = Invoke-Api Post "$DomainApi/api/v1/exchange-proposals" $aliceTok @{
    recipientUserId         = $bobCandidate.candidateUserId
    proposerTeachesSkillId  = $SkillBackend
    proposerTeachesLevelId  = $LevelBackendInt
    proposerTopicCode       = "rest-apis"
    recipientTeachesSkillId = $SkillEnglish
    recipientTeachesLevelId = $LevelEnglishB1
    recipientTopicCode      = "technical-interviews"
}
Invoke-Api Post "$DomainApi/api/v1/exchange-proposals/$($proposal.proposalId)/respond" $bobTok @{
    decision = "ACCEPT"
} | Out-Null

Write-Host "7) Agendando sesión en Go con idempotencia (B10-B11)..."
$startUtc = (Get-Date).ToUniversalTime().AddMinutes(2).ToString("yyyy-MM-ddTHH:mm:ssZ")
$endUtc = (Get-Date).ToUniversalTime().AddMinutes(62).ToString("yyyy-MM-ddTHH:mm:ssZ")
$idemCreate = [guid]::NewGuid().ToString()

$sessionPayload = @{
    proposalId        = $proposal.proposalId
    scheduledStartAt  = $startUtc
    scheduledEndAt    = $endUtc
    agreementAccepted = $true
    agreementVersion  = "v1.0-pilot"
}
$session1 = Invoke-Api Post "$SessionsApi/api/v1/sessions" $aliceTok $sessionPayload @{ "Idempotency-Key" = $idemCreate }
$session2 = Invoke-Api Post "$SessionsApi/api/v1/sessions" $aliceTok $sessionPayload @{ "Idempotency-Key" = $idemCreate }
if ($session1.sessionId -ne $session2.sessionId) {
    throw "Fallo de idempotencia en creación de sesión."
}
Write-Host "   Sesión $($session1.sessionId) creada en estado $($session1.status)."

Write-Host "8) Probando acuerdo bilateral, bloqueo a terceros y emisión de token LiveKit (B11-B12)..."
Assert-Fails "Entrar a sala sin aceptación de acuerdo del segundo participante" {
    Invoke-Api Post "$SessionsApi/api/v1/sessions/$($session1.sessionId)/room-token" $aliceTok
}

$scheduled = Invoke-Api Post "$SessionsApi/api/v1/sessions/$($session1.sessionId)/agreement" $bobTok @{
    agreementAccepted = $true; agreementVersion = "v1.0-pilot"
}
Write-Host "   Acuerdo aceptado por Bob -> estado $($scheduled.status)."

Assert-Fails "Tercero no participante (reviewer) solicitando token de sala" {
    Invoke-Api Post "$SessionsApi/api/v1/sessions/$($session1.sessionId)/room-token" $revTok
}

$roomAlice = Invoke-Api Post "$SessionsApi/api/v1/sessions/$($session1.sessionId)/room-token" $aliceTok
$roomBob = Invoke-Api Post "$SessionsApi/api/v1/sessions/$($session1.sessionId)/room-token" $bobTok
Write-Host "   Tokens LiveKit emitidos para sala $($roomAlice.roomName) (estado: $($roomBob.sessionStatus))."

Write-Host "9) Cerrando sesión en Go (Contrato 4) y confirmando bilateralmente en Java (Contrato 5 / B14-B15)..."
Invoke-Api Post "$SessionsApi/api/v1/sessions/$($session1.sessionId)/close" $aliceTok @{
    terminalSessionState      = "COMPLETED_OBSERVED"
    technicalIncidentObserved = $false
} | Out-Null

$conf1 = Invoke-Api Put "$DomainApi/api/v1/exchanges/$($proposal.proposalId)/confirmations/me" $aliceTok @{
    sessionId                = $session1.sessionId
    outcomeDeclaration       = "COMPLETED"
    bothBlocksExecuted       = $true
    learnedSkillProgressNote = "Practiqué estructuras para explicar trade-offs."
    wouldRepeatWithPartner   = $true
}
$conf2 = Invoke-Api Put "$DomainApi/api/v1/exchanges/$($proposal.proposalId)/confirmations/me" $bobTok @{
    sessionId                = $session1.sessionId
    outcomeDeclaration       = "COMPLETED"
    bothBlocksExecuted       = $true
    learnedSkillProgressNote = "Aprendí idempotencia con Idempotency-Key."
    wouldRepeatWithPartner   = $true
}
Write-Host "   Resolución final: $($conf2.exchangeResolutionStatus) | Hecho registrado: $($conf2.creditEligibilityFactRecorded)"

$history = Invoke-Api Get "$DomainApi/api/v1/exchanges/history/me" $aliceTok
$metrics = Invoke-Api Get "$DomainApi/api/v1/admin/metrics/pilot" $revTok
Write-Host "10) Historial de Alice ($($history.Count) registros) y Métricas del Piloto:"
$metrics | ConvertTo-Json
