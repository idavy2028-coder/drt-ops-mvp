using System;
using System.IO;
using System.Linq;
using System.Threading;

public static class Task12FakeDocker
{
    private static string Env(string name) { return Environment.GetEnvironmentVariable(name) ?? ""; }

    public static int Main(string[] args)
    {
        var log = Env("TASK12_FAKE_LOG");
        if (!String.IsNullOrEmpty(log))
        {
            var route = new[] { "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "DOCKER_API_VERSION" }
                .Select(Env);
            File.AppendAllText(log, String.Join("\t", args) + "\tENV=" + String.Join("|", route) + Environment.NewLine);
        }

        var mode = Env("TASK12_FAKE_MODE");
        var command = String.Join(" ", args);
        if (mode == "timeout" && command.Contains("container ls"))
        {
            Thread.Sleep(30000);
            return 0;
        }
        if (mode == "fail-list" && command.Contains("container ls"))
        {
            Console.Error.Write("TASK12_SYNTHETIC_SECRET_EXCEPTION");
            return 23;
        }

        var statePath = Env("TASK12_FAKE_STATE");
        var containerRemoved = !String.IsNullOrEmpty(statePath) && File.Exists(statePath);
        if (command.Contains("container ls"))
        {
            if (!containerRemoved)
            {
                Console.WriteLine(Env("TASK12_FAKE_CONTAINER_ID"));
                var secondId = Env("TASK12_FAKE_CONTAINER_ID_2");
                if (!String.IsNullOrEmpty(secondId)) Console.WriteLine(secondId);
            }
            return 0;
        }
        if (command.Contains("volume ls"))
        {
            Console.WriteLine(Env("TASK12_FAKE_VOLUME_NAME"));
            return 0;
        }
        if (command.Contains("container inspect"))
        {
            Console.Write(Env("TASK12_FAKE_CONTAINER_JSON"));
            return 0;
        }
        if (command.Contains("volume inspect"))
        {
            Console.Write(Env("TASK12_FAKE_VOLUME_JSON"));
            return 0;
        }
        if (command.Contains("container rm"))
        {
            if (mode == "fail-container") { Console.Error.Write("TASK12_SYNTHETIC_SECRET_EXCEPTION"); return 28; }
            if (!String.IsNullOrEmpty(statePath)) File.WriteAllText(statePath, "container-removed");
            return 0;
        }
        if (command.Contains("volume rm"))
        {
            if (mode == "fail-volume") { Console.Error.Write("TASK12_SYNTHETIC_SECRET_EXCEPTION"); return 29; }
            return 0;
        }
        Console.Error.Write("TASK12_SYNTHETIC_UNEXPECTED_COMMAND");
        return 64;
    }
}
