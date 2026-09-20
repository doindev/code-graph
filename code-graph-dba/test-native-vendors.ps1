param([ValidateSet('mongodb','redis')][Parameter(Mandatory=$true)][string]$Engine,[switch]$Performance)
$ErrorActionPreference='Stop'
$image=if($Engine -eq 'mongodb'){'mongo@sha256:4968f22d0c6c10ef29952f3e807f62872ba22b3312f25803564fbfc08255efc2'}else{'redis@sha256:c1e88455c85225310bbea54816e9c3f4b5295815e6dbf80c34d40afc6df28275'}
$internalPort=if($Engine -eq 'mongodb'){27017}else{6379}
$prior=@(docker image ls --no-trunc --format '{{.ID}}')
$owner='cgraph-native-'+$Engine+'-'+[guid]::NewGuid().ToString('N')
$container=$null
$imageId=$null
try {
    docker pull $image
    if($LASTEXITCODE -ne 0){throw 'Native fixture image download failed.'}
    $imageId=docker image inspect $image --format '{{.Id}}'
    $digest=docker image inspect $image --format '{{index .RepoDigests 0}}'
    $container=docker run -d --name $owner --label "io.doindev.codegraph.test=$owner" --memory 1g --cpus 2 -p "127.0.0.1::$internalPort" $digest
    if($LASTEXITCODE -ne 0){throw 'Native fixture startup failed.'}
    $env:NATIVE_TEST_ENGINE=$Engine
    $env:NATIVE_TEST_OWNER=$owner
    $env:NATIVE_TEST_IMAGE=$digest
    $env:NATIVE_TEST_PORT=(docker port $container "$internalPort/tcp").Trim().Split(':')[-1]
    Write-Output "Owned native fixture: $owner; image $digest; loopback port $env:NATIVE_TEST_PORT"
    $deadline=[DateTime]::UtcNow.AddSeconds(90)
    do {
        if($Engine -eq 'mongodb'){
            $ready=(@(docker exec $container mongosh --quiet --eval 'db.runCommand({ping:1}).ok' 2>&1) -contains '1')
        }else{$ready=(@(docker exec $container redis-cli PING 2>&1) -contains 'PONG')}
        if($ready){break}
        if([DateTime]::UtcNow -gt $deadline){throw 'Native fixture readiness timed out.'}
        Start-Sleep -Milliseconds 500
    }while($true)
    mvn -q -pl code-graph-dba -am test '-Dtest=NativeVendorTest,NativeReviewTest,NativeMongoRenameTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true' '-Dtest.jvm.args=-Xmx256m -XX:MaxDirectMemorySize=64m'
    if($LASTEXITCODE -ne 0){throw 'Native vendor gate failed.'}
    if($Performance){
        $env:NATIVE_TEST_PERFORMANCE='true'
        for($run=1;$run -le 3;$run++){
            $env:NATIVE_TEST_RUN=[string]$run
            # Separate Maven invocations give each measured run a fresh test JVM.
            mvn -q -pl code-graph-dba -am test '-Dtest=NativePerformanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true' '-Dtest.jvm.args=-Xmx256m -XX:MaxDirectMemorySize=64m'
            if($LASTEXITCODE -ne 0){throw "Native performance run $run failed; do not omit this sample."}
        }
    }
}finally{
    if($container){
        $label=docker inspect $container --format '{{index .Config.Labels "io.doindev.codegraph.test"}}'
        if($label -eq $owner){docker rm --force --volumes $container}
        else{Write-Warning 'Ownership mismatch: refusing cleanup.'}
    }
    if($imageId -and $prior -notcontains $imageId){
        $users=@(docker ps -aq --filter "ancestor=$imageId")
        if($users.Count -eq 0){docker image rm $image}
        else{Write-Warning 'New image still in use; preserving it.'}
    }
    foreach($key in @('NATIVE_TEST_ENGINE','NATIVE_TEST_OWNER','NATIVE_TEST_PORT','NATIVE_TEST_IMAGE','NATIVE_TEST_PERFORMANCE','NATIVE_TEST_RUN')){
        Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
    }
}
