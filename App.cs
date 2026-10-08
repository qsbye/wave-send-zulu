using System;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AirScanQR
{
    public static class App
    {
        [STAThread]
        public static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            Application.ThreadException += (s, args) => CrashExit(args.Exception);
            AppDomain.CurrentDomain.UnhandledException += (s, args) =>
            {
                CrashExit(args.ExceptionObject as Exception);
            };
            TaskScheduler.UnobservedTaskException += (s, args) => args.SetObserved();

            var args = Environment.GetCommandLineArgs();
            var selfTest = Array.Exists(args, a => a == "--selftest");
            Application.Run(new MainWindow(selfTest));
        }

        public static void CrashExit(Exception ex)
        {
            try
            {
                var msg = ex?.ToString() ?? "未知错误 / Unknown error";
                MessageBox.Show(msg, "AirScanQR", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            catch { }
            Environment.Exit(1);
        }
    }
}
