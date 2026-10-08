// AirFountainZulu 单文件启动器（.NET Framework 4.x，WinExe 无控制台窗口）
// 启动时将内嵌的分发目录 zip 释出到 %LOCALAPPDATA%\AirFountainZulu\<version>\，
// 随后启动其中的 AirFountainZulu.exe。版本一致则跳过释出直接启动。
using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Windows.Forms;

internal static class Launcher
{
    private const string AppName = "AirFountainZulu";
    private const string Version = "26.10.8.18";
    private const string InnerExe = AppName + ".exe";
    private const string ResourceName = "payload.zip";

    [STAThread]
    private static int Main()
    {
        try
        {
            string dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                AppName, Version);
            string exe = Path.Combine(dir, InnerExe);
            string marker = Path.Combine(dir, ".extracted");

            if (!File.Exists(marker) || !File.Exists(exe))
            {
                Extract(dir, marker);
            }

            var psi = new ProcessStartInfo(exe)
            {
                WorkingDirectory = dir,
                UseShellExecute = true,
            };
            Process.Start(psi);
            return 0;
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                "启动失败：" + ex.Message,
                AppName,
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            return 1;
        }
    }

    private static void Extract(string dir, string marker)
    {
        Directory.CreateDirectory(dir);

        // 先解到临时目录再整体改名，避免中途失败留下半成品。
        string tmp = dir + ".tmp-" + Guid.NewGuid().ToString("N");
        Directory.CreateDirectory(tmp);
        try
        {
            string zipPath = Path.Combine(tmp, ResourceName);
            using (Stream src = Assembly.GetExecutingAssembly().GetManifestResourceStream(ResourceName))
            {
                if (src == null) throw new InvalidOperationException("内嵌资源缺失");
                using (FileStream dst = File.Create(zipPath))
                {
                    src.CopyTo(dst);
                }
            }
            ZipFile.ExtractToDirectory(zipPath, tmp);
            File.Delete(zipPath);

            // 清掉旧版本目录后落位。
            string old = dir + ".old-" + Guid.NewGuid().ToString("N");
            if (Directory.Exists(dir)) Directory.Move(dir, old);
            // tmp 下就是分发目录内容（zip 打包时用的是目录内容本身）。
            Directory.Move(tmp, dir);
            if (Directory.Exists(old))
            {
                try { Directory.Delete(old, true); } catch { /* 下次启动再清理 */ }
            }
            File.WriteAllText(marker, Version);
        }
        finally
        {
            if (Directory.Exists(tmp))
            {
                try { Directory.Delete(tmp, true); } catch { /* 忽略 */ }
            }
        }
    }
}
