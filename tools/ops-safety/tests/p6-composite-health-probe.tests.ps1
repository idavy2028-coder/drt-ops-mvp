$ErrorActionPreference='Stop'
$ops=Split-Path -Parent $PSScriptRoot
. (Join-Path $ops 'p6-composite-isolation-lib.ps1')
. (Join-Path $ops 'p6-composite-isolation-pipeline.ps1')
. (Join-Path $ops 'p6-composite-real-runtime.ps1')
Add-Type -TypeDefinition @"
using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;
public sealed class P6HealthTestServer : IDisposable {
 private readonly TcpListener listener;
 private readonly Task worker;
 public readonly int Port;
 public string Request;
 public P6HealthTestServer(string path, int status, string body) {
  listener=new TcpListener(IPAddress.Loopback,0); listener.Start();
  Port=((IPEndPoint)listener.LocalEndpoint).Port;
  worker=Task.Run(async()=>{
   using(var client=await listener.AcceptTcpClientAsync()) {
    client.ReceiveTimeout=3000; client.SendTimeout=3000;
    using(var stream=client.GetStream()) {
     var header=new StringBuilder(); int b;
     while(header.Length<8192 && (b=stream.ReadByte())>=0) {
      header.Append((char)b); if(header.ToString().EndsWith("\r\n\r\n")) break;
     }
     Request=header.ToString().Split('\r')[0];
     int code=Request=="GET "+path+" HTTP/1.1"?status:401;
     var payload=Encoding.UTF8.GetBytes(body);
     var response=Encoding.ASCII.GetBytes("HTTP/1.1 "+code+" Test\r\nContent-Type: application/json\r\nContent-Length: "+payload.Length+"\r\nConnection: close\r\n\r\n");
     stream.Write(response,0,response.Length);stream.Write(payload,0,payload.Length);
    }
   }
  });
 }
 public void Dispose(){listener.Stop();if(!worker.Wait(5000))throw new Exception("TEST_SERVER_TIMEOUT");}
}
"@
function Test-Probe($Role,$Path,$Status,$Body,$Expected) {
    # Isolate filesystem/process ownership guards; exercise the unmodified HTTP consumer.
    function Assert-P6RuntimeGuard($Context,[string]$Pending='') {}
    $server=New-Object P6HealthTestServer($Path,$Status,$Body)
    try {
        $context=[pscustomobject]@{Receipt=@{Ports=@(1,$server.Port,$server.Port)};Lifetime=(New-P6ResourceLifetime);Tickets=@{}}
        # Reject immediately after one unsuccessful request, instead of waiting 120 seconds.
        $context.Tickets[$Role]=@{Process=@{HasExited=$true}}
        $accepted=$false
        try {Wait-P6RuntimeReady $context $Role;$accepted=$true}
        catch {if($_.Exception.Message -cne 'REHEARSAL_READY_FAILED'){throw}}
        if($accepted -ne $Expected){throw ('HEALTH_PROBE_WRONG_RESULT_'+$Role+'_'+$Status+'_'+$server.Request)}
    } finally {$server.Dispose()}
}
# Catches accidental API readiness use, global path replacement, and weakened response checks.
Test-Probe API '/actuator/health' 200 '{"status":"UP"}' $true
Test-Probe GW '/actuator/health/readiness' 200 '{"status":"UP"}' $true
Test-Probe API '/actuator/health' 200 '{"status":"DOWN"}' $false
Test-Probe API '/actuator/health' 401 '{"status":"UP"}' $false
Test-Probe API '/actuator/health' 302 '{"status":"UP"}' $false
Test-Probe API '/actuator/health' 201 '{"status":"UP"}' $false
'HEALTH_PROBE_TESTS=PASS COUNT=6 REAL_HTTP=true'
