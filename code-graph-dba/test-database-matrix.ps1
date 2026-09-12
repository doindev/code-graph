param([string[]]$Databases = @('postgresql','mariadb','mysql'))
$ErrorActionPreference = 'Stop'
$matrixWorkspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$matrixSpecs = @{
    postgresql = @{ Image='postgres:16'; Port=5432; User='postgres'; Secret='POSTGRES_PASSWORD'; Db='postgres'; Mount='/var/lib/postgresql/data' }
    mariadb = @{ Image='mariadb:11.4'; Port=3306; User='root'; Secret='MARIADB_ROOT_PASSWORD'; Db='mysql'; Mount='/var/lib/mysql' }
    mysql = @{ Image='mysql:8.4'; Port=3306; User='root'; Secret='MYSQL_ROOT_PASSWORD'; Db='mysql'; Mount='/var/lib/mysql' }
    oracle = @{ Image='gvenzl/oracle-free:23-slim'; Port=1521; User='system'; Secret='ORACLE_PASSWORD'; Db='FREEPDB1'; Mount=$null }
}
Push-Location $matrixWorkspace
try {
    foreach ($matrixDatabase in $Databases) {
        if (-not $matrixSpecs.ContainsKey($matrixDatabase)) { throw 'Unsupported disposable database selection' }
        $matrixSpec = $matrixSpecs[$matrixDatabase]
        $matrixOwner = 'code-graph-dba-matrix-' + [guid]::NewGuid().ToString('N')
        $matrixSecret = [guid]::NewGuid().ToString('N')
        $matrixExisted = @(docker image ls --format '{{.Repository}}:{{.Tag}}') -contains $matrixSpec.Image
        $matrixCreated = $false
        try {
            $matrixResources = @('--memory','1g','--cpus','2')
            if ($matrixDatabase -eq 'oracle') { $matrixResources = @('--memory','3g','--cpus','2','--shm-size','1g') }
            if ($matrixSpec.Mount) { $matrixResources += @('--tmpfs',$matrixSpec.Mount) }
            docker run --detach --rm --name $matrixOwner --label "code-graph.dba.test=$matrixOwner" @matrixResources -e ($matrixSpec.Secret + '=' + $matrixSecret) -p ('127.0.0.1::' + $matrixSpec.Port) $matrixSpec.Image | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Cannot create disposable database' }
            $matrixCreated = $true
            $matrixDeadline = [DateTime]::UtcNow.AddSeconds(300)
            do {
                if ($matrixDatabase -eq 'postgresql') { docker exec $matrixOwner pg_isready -U postgres *> $null }
                elseif ($matrixDatabase -eq 'mariadb') { docker exec $matrixOwner mariadb-admin ping --silent *> $null }
                elseif ($matrixDatabase -eq 'oracle') { docker exec $matrixOwner healthcheck.sh *> $null }
                else { docker exec $matrixOwner mysqladmin ping --silent *> $null }
                if ($LASTEXITCODE -eq 0) { break }
                if ([DateTime]::UtcNow -gt $matrixDeadline) { throw 'Database readiness timeout' }
                Start-Sleep -Milliseconds 500
            } while ($true)
            $matrixBinding = docker port $matrixOwner ($matrixSpec.Port.ToString() + '/tcp')
            if ($matrixBinding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Unexpected database port binding' }
            $env:DBA_MATRIX_OWNER = $matrixOwner
            $env:DBA_MATRIX_TEMPLATE = $matrixDatabase
            $env:DBA_MATRIX_URL = 'jdbc:' + $matrixDatabase + '://127.0.0.1:' + $Matches[1] + '/' + $matrixSpec.Db
            if ($matrixDatabase -eq 'oracle') { $env:DBA_MATRIX_URL = 'jdbc:oracle:thin:@//127.0.0.1:' + $Matches[1] + '/' + $matrixSpec.Db }
            if ($matrixDatabase -eq 'mysql') { $env:DBA_MATRIX_URL += '?allowPublicKeyRetrieval=true&sslMode=DISABLED' }
            $env:DBA_MATRIX_USER = $matrixSpec.User
            $env:DBA_MATRIX_PASSWORD = $matrixSecret
            mvn -pl code-graph-dba -am test '-Dtest=DockerDatabaseTest' '-Dsurefire.failIfNoSpecifiedTests=false'
            if ($LASTEXITCODE -ne 0) { throw ('Database validation failed: ' + $matrixDatabase) }
        } finally {
            Remove-Item Env:DBA_MATRIX_OWNER,Env:DBA_MATRIX_TEMPLATE,Env:DBA_MATRIX_URL,Env:DBA_MATRIX_USER,Env:DBA_MATRIX_PASSWORD -ErrorAction SilentlyContinue
            if ($matrixCreated) {
                $matrixLabels = docker inspect --format '{{json .Config.Labels}}' $matrixOwner | ConvertFrom-Json
                if ($matrixLabels.'code-graph.dba.test' -eq $matrixOwner) { docker rm --force $matrixOwner | Out-Null }
                else { Write-Warning 'Ownership mismatch: container cleanup refused' }
            }
            if (-not $matrixExisted) {
                $matrixUsers = @(docker ps --all --quiet --filter ('ancestor=' + $matrixSpec.Image))
                if ($matrixUsers.Count -eq 0) { docker image rm $matrixSpec.Image | Out-Null }
            }
        }
    }
} finally { Pop-Location }
