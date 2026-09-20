param([ValidateSet('cluster','sentinel')][Parameter(Mandatory=$true)][string]$Topology)
$ErrorActionPreference='Stop'
$image='redis@sha256:c1e88455c85225310bbea54816e9c3f4b5295815e6dbf80c34d40afc6df28275'
$prior=@(docker image ls --no-trunc --format '{{.ID}}')
$owner='cgraph-topology-'+$Topology+'-'+[guid]::NewGuid().ToString('N')
$container=$null
$imageId=$null
try {
    docker pull $image
    if($LASTEXITCODE -ne 0){throw 'Redis fixture download failed'}
    $imageId=docker image inspect $image --format '{{.Id}}'
    # Reserve/check three client and three bus ports before exposing only loopback mappings.
    for($attempt=0;$attempt -lt 20;$attempt++){
        $base=Get-Random -Minimum 22000 -Maximum 29000
        $listeners=@()
        try {foreach($port in @($base,($base+1),($base+2),($base+10000),($base+10001),($base+10002))){$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$port);$listener.Start();$listeners+=$listener};break}
        catch {if($attempt -eq 19){throw}}
        finally {foreach($listener in $listeners){$listener.Stop()}}
    }
    $args=@('run','-d','--name',$owner,'--label',"io.doindev.codegraph.test=$owner",'--memory','768m','--cpus','2')
    foreach($port in @($base,($base+1),($base+2),($base+10000),($base+10001),($base+10002))){$args+=@('-p',"127.0.0.1:${port}:${port}")}
    $args+=@($image,'sleep','infinity');$container=docker @args
    if($LASTEXITCODE -ne 0){throw 'Redis topology fixture startup failed'}
    if($Topology -eq 'cluster'){
        foreach($port in @($base,($base+1),($base+2))){
            docker exec -d $container redis-server --port $port --bind 0.0.0.0 --protected-mode no --cluster-enabled yes --cluster-config-file "/tmp/node-$port.conf" --cluster-node-timeout 5000 --cluster-announce-ip 127.0.0.1 --cluster-announce-port $port --cluster-announce-bus-port ($port+10000) --appendonly no
        }
    }else{
        docker exec -d $container redis-server --port $base --bind 0.0.0.0 --protected-mode no
        docker exec -d $container redis-server --port ($base+1) --bind 0.0.0.0 --protected-mode no --replicaof 127.0.0.1 $base
        # Test-generated configuration lives only in the owned temporary directory/container.
        $fixtureDir=Join-Path ([IO.Path]::GetTempPath()) $owner
        New-Item -ItemType Directory -Path $fixtureDir | Out-Null
        $config=Join-Path $fixtureDir 'sentinel.conf'
        [IO.File]::WriteAllText($config,"port $($base+2)`nbind 0.0.0.0`nprotected-mode no`nsentinel monitor cgraph 127.0.0.1 $base 1`nsentinel down-after-milliseconds cgraph 5000`n")
        docker cp $config "${container}:/tmp/sentinel.conf"
        docker exec -d $container redis-server /tmp/sentinel.conf --sentinel
    }
    $deadline=[DateTime]::UtcNow.AddSeconds(30)
    do{$ready=@(docker exec $container redis-cli -p $base PING 2>&1) -contains 'PONG';if($ready){break};if([DateTime]::UtcNow -gt $deadline){throw 'Redis readiness timed out'};Start-Sleep -Milliseconds 200}while($true)
    if($Topology -eq 'cluster'){
        docker exec $container redis-cli --cluster create "127.0.0.1:$base" "127.0.0.1:$($base+1)" "127.0.0.1:$($base+2)" --cluster-replicas 0 --cluster-yes
        if($LASTEXITCODE -ne 0){throw 'Cluster setup failed'}
        do{$ready=@(docker exec $container redis-cli -p $base cluster info 2>&1) -contains 'cluster_state:ok';if($ready){break};if([DateTime]::UtcNow -gt $deadline){throw 'Cluster slots unavailable'};Start-Sleep -Milliseconds 200}while($true)
    }
    $env:NATIVE_TEST_TOPOLOGY=$Topology;$env:NATIVE_TEST_PORT=[string]$(if($Topology -eq 'cluster'){$base}else{$base+2});$env:NATIVE_TEST_OWNER=$owner
    Write-Output "Owned $Topology fixture $owner on $env:NATIVE_TEST_PORT"
    mvn -q -pl code-graph-dba -am test '-Dtest=NativeTopologyTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true' '-Dtest.jvm.args=-Xmx256m -XX:MaxDirectMemorySize=64m'
    if($LASTEXITCODE -ne 0){throw 'Redis topology gate failed'}
}finally{
    if($container){$label=docker inspect $container --format '{{index .Config.Labels "io.doindev.codegraph.test"}}';if($label -eq $owner){docker rm --force --volumes $container}else{Write-Warning 'Ownership mismatch; container preserved'}}
    if($imageId -and $prior -notcontains $imageId -and @(docker ps -aq --filter "ancestor=$imageId").Count -eq 0){docker image rm $image}
    if($fixtureDir){$resolved=[IO.Path]::GetFullPath($fixtureDir);$expected=[IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) $owner));if($resolved -eq $expected -and (Split-Path $resolved -Leaf) -eq $owner){Remove-Item -LiteralPath $resolved -Recurse -Force}}
    foreach($name in @('NATIVE_TEST_TOPOLOGY','NATIVE_TEST_PORT','NATIVE_TEST_OWNER')){Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue}
}
