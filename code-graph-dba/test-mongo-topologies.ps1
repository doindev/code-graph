param([ValidateSet('replica_set','sharded')][Parameter(Mandatory=$true)][string]$Topology)
$ErrorActionPreference='Stop'
$image='mongo@sha256:4968f22d0c6c10ef29952f3e807f62872ba22b3312f25803564fbfc08255efc2'
$prior=@(docker image ls --no-trunc --format '{{.ID}}')
$owner='cgraph-mongo-topology-'+[guid]::NewGuid().ToString('N')
$container=$null
$imageId=$null
function Invoke-Mongo([int]$Port,[string]$Expression){
    $output=@(docker exec $container mongosh --quiet --port $Port --eval $Expression 2>&1)
    if($LASTEXITCODE -ne 0){throw "Owned MongoDB fixture setup failed: $output"}
    return $output
}
try{
    docker pull $image
    if($LASTEXITCODE -ne 0){throw 'MongoDB fixture download failed'}
    $imageId=docker image inspect $image --format '{{.Id}}'
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $listener.Start();$port=$listener.LocalEndpoint.Port;$listener.Stop()
    $container=docker run -d --name $owner --label "io.doindev.codegraph.test=$owner" --memory 1500m --cpus 2 -p "127.0.0.1:${port}:${port}" $image sleep infinity
    if($LASTEXITCODE -ne 0){throw 'MongoDB topology container failed'}
    docker exec $container mkdir -p /tmp/replica /tmp/config /tmp/shard
    if($Topology -eq 'replica_set'){
        docker exec -d $container mongod --port $port --bind_ip_all --replSet cgraph --dbpath /tmp/replica --wiredTigerCacheSizeGB 0.25
        $initialPort=$port
    }else{
        # Bound cold-member change-stream ordering latency in this disposable fixture.
        # Never apply this server tuning to saved/user connections.
        docker exec -d $container mongod --port 27019 --bind_ip_all --configsvr --replSet config --dbpath /tmp/config --wiredTigerCacheSizeGB 0.25 --setParameter periodicNoopIntervalSecs=1
        docker exec -d $container mongod --port 27018 --bind_ip_all --shardsvr --replSet shard --dbpath /tmp/shard --wiredTigerCacheSizeGB 0.25 --setParameter periodicNoopIntervalSecs=1
        $initialPort=27019
    }
    $deadline=[DateTime]::UtcNow.AddSeconds(90)
    do{$ready=@(docker exec $container mongosh --quiet --port $initialPort --eval 'db.runCommand({ping:1}).ok' 2>&1) -contains '1';if($ready){break};if([DateTime]::UtcNow -gt $deadline){throw 'MongoDB readiness timed out'};Start-Sleep -Milliseconds 500}while($true)
    if($Topology -eq 'replica_set'){
        Invoke-Mongo $port "rs.initiate({_id:'cgraph',members:[{_id:0,host:'127.0.0.1:$port'}]})"
    }else{
        Invoke-Mongo 27019 "rs.initiate({_id:'config',configsvr:true,members:[{_id:0,host:'127.0.0.1:27019'}]})"
        Invoke-Mongo 27018 "rs.initiate({_id:'shard',members:[{_id:0,host:'127.0.0.1:27018'}]})"
    }
    do{$ready=@(Invoke-Mongo $initialPort 'db.hello().isWritablePrimary') -contains 'true';if($ready){break};if([DateTime]::UtcNow -gt $deadline){throw 'MongoDB election timed out'};Start-Sleep -Milliseconds 500}while($true)
    if($Topology -eq 'sharded'){
        docker exec -d $container mongos --port $port --bind_ip_all --configdb config/127.0.0.1:27019
        do{$ready=@(docker exec $container mongosh --quiet --port $port --eval 'db.runCommand({ping:1}).ok' 2>&1) -contains '1';if($ready){break};if([DateTime]::UtcNow -gt $deadline){throw 'MongoDB router timed out'};Start-Sleep -Milliseconds 500}while($true)
        Invoke-Mongo $port "sh.addShard('shard/127.0.0.1:27018')"
    }
    $env:MONGO_TOPOLOGY_PORT=[string]$port;$env:MONGO_TOPOLOGY_KIND=$Topology;$env:MONGO_TOPOLOGY_OWNER=$owner
    Write-Output "Owned $Topology fixture $owner; pinned image $image; loopback port $port"
    mvn -q -pl code-graph-dba -am test '-Dtest=MongoTopologyTest,NativeMongoTransactionTest,NativeMongoDocumentTest,NativeMongoStreamTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true' '-Dtest.jvm.args=-Xmx256m -XX:MaxDirectMemorySize=64m'
    if($LASTEXITCODE -ne 0){throw 'MongoDB topology gate failed'}
}finally{
    if($container){$label=docker inspect $container --format '{{index .Config.Labels "io.doindev.codegraph.test"}}';if($label -eq $owner){docker rm --force --volumes $container}else{Write-Warning 'Ownership mismatch; container preserved'}}
    if($imageId -and $prior -notcontains $imageId -and @(docker ps -aq --filter "ancestor=$imageId").Count -eq 0){docker image rm $image}
    foreach($name in @('MONGO_TOPOLOGY_PORT','MONGO_TOPOLOGY_KIND','MONGO_TOPOLOGY_OWNER')){Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue}
}
