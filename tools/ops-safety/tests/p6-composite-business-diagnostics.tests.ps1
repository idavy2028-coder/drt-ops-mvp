$ErrorActionPreference='Stop'
$ops=Split-Path -Parent $PSScriptRoot
$repo=[IO.Path]::GetFullPath((Join-Path $ops '../..'))
. (Join-Path $ops 'p6-composite-isolation-lib.ps1')
$parent=Join-Path $repo '.superpowers/sdd/2026-09-06-p6-2-local-isolation-rehearsal'
$testRoot=Join-Path $parent ('native-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testRoot);Protect-P6RunAcl $testRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead((Join-Path $repo 'apps/api/target/drt-ops-api-0.1.0-SNAPSHOT.jar'))
try{foreach($entry in $archive.Entries){if($entry.FullName -cmatch '^BOOT-INF/lib/jackson-(annotations|core|databind)-[0-9.]+\.jar$'){[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $testRoot $entry.Name))}}}finally{$archive.Dispose()}
$classes=Join-Path $testRoot 'classes';[void][IO.Directory]::CreateDirectory($classes)
$cp=$classes+';'+$testRoot+'/*'
& 'C:/Program Files/Java/jdk-21.0.10/bin/javac.exe' -encoding UTF-8 -cp $cp -d $classes (Join-Path $ops 'fixtures/P6CompositeBusinessTool.java') (Join-Path $ops 'fixtures/P6CompositeBusinessToolContractTest.java') (Join-Path $ops 'fixtures/P6CompositeBusinessFlowTest.java')
if($LASTEXITCODE -ne 0){throw 'BUSINESS_TEST_COMPILE_FAILED'}
& 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe' -cp $cp P6CompositeBusinessToolContractTest
if($LASTEXITCODE -ne 0){throw 'BUSINESS_CONTRACT_FAILED'}
& 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe' -cp $cp P6CompositeBusinessFlowTest $testRoot
if($LASTEXITCODE -ne 0){throw 'BUSINESS_FLOW_FAILED'}
. (Join-Path $ops 'p6-composite-isolation-pipeline.ps1')
. (Join-Path $ops 'p6-composite-real-runtime.ps1')
function Test-ActualBusinessDiagnosticConsumer {
 # Only isolate resource guards: the real Java spec, main, bounded drain and report projection run.
 function Assert-P6RuntimeGuard($Context,[string]$Pending='') {}
 $fixture=Get-ChildItem (Join-Path $testRoot '.tmp/p6iso') -Directory | Select-Object -First 1
 $ctx=[pscustomobject]@{Receipt=@{RunDirectory=$fixture.FullName;RunId=$fixture.Name.Substring(7)};ClassPath=$cp;Lifetime=(New-P6ResourceLifetime);Tickets=@{};ShortTickets=(New-Object 'Collections.Generic.List[object]');ToolProcessIds=(New-Object 'Collections.Generic.List[int]');Plan=$null;Evidence=(New-Object 'Collections.Generic.List[object]')}
 # Deliberately missing run identity rejects before any HTTP or JDBC call.
 $caught='';try{Invoke-P6RuntimeJava $ctx P6CompositeBusinessTool (Get-P6ChildEnvironment $fixture.FullName) BUSINESS 10000}catch{$caught=$_.Exception.Message}
 if($caught -cne 'REHEARSAL_BUSINESS_ASSERTION_FAILED' -or $ctx.Evidence.Count -ne 1 -or $ctx.Evidence[0].BusinessFailure.Step -cne 'OWNER_VALIDATE' -or $ctx.Evidence[0].BusinessFailure.AssertionId -cne 'B002'){throw 'ACTUAL_BUSINESS_CONSUMER_FAILED'}
 $serialized=$ctx.Evidence|ConvertTo-Json -Depth 8 -Compress
 if($serialized.Contains($fixture.FullName) -or $ctx.ShortTickets.Count -ne 0){throw 'ACTUAL_BUSINESS_CONSUMER_LEAK'}
 foreach($id in $ctx.ToolProcessIds){if(Get-Process -Id $id -ErrorAction SilentlyContinue){throw 'ACTUAL_BUSINESS_PROCESS_RETAINED'}}
 'BUSINESS_DIAGNOSTIC_INTEGRATION=PASS REAL_HELPER_AND_RUNNER=true'
}
Test-ActualBusinessDiagnosticConsumer
'BUSINESS_TEST_STORAGE=RETAINED_SYNTHETIC_ONLY'
