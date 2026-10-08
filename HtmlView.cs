using System;
using System.IO;
using System.Reflection;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace AirScanQR
{
    public static class Pages
    {
        public const string Origin = "https://airscan.local";

        public static Stream Open(string resourceName)
        {
            try
            {
                return Assembly.GetExecutingAssembly().GetManifestResourceStream(resourceName);
            }
            catch
            {
                return null;
            }
        }
    }

    /// <summary>
    /// 单个离线页面容器：WebView2 通过 WebResourceRequested 拦截
    /// https://airscan.local/* 并返回内嵌在 exe 中的 HTML，全程无网络访问。
    /// </summary>
    public class HtmlView : Panel
    {
        private readonly WebView2 webView;
        private readonly string resourceName;
        private readonly string fileName;
        private bool configured;
        private string initError;

        public bool Ready { get; private set; }
        public string InitError => initError;
        public string LastDownloadPath { get; private set; }

        public HtmlView(string resourceName, string fileName)
        {
            this.resourceName = resourceName;
            this.fileName = fileName;
            Dock = DockStyle.Fill;
            BackColor = System.Drawing.Color.White;
            Visible = false;

            webView = new WebView2 { Dock = DockStyle.Fill };
            webView.CoreWebView2InitializationCompleted += (s, e) =>
            {
                if (!e.IsSuccess)
                    initError = e.InitializationException?.Message ?? "WebView2 初始化失败";
            };
            Controls.Add(webView);
        }

        public async Task<bool> EnsureReadyAsync()
        {
            if (Ready) return true;            if (IsDisposed || Disposing) return false;

            try
            {
                await webView.EnsureCoreWebView2Async(await GetEnvironmentAsync());
            }
            catch (Exception ex)
            {
                initError = ex.Message;
                return false;
            }

            var core = webView.CoreWebView2;
            if (core == null)
            {
                if (string.IsNullOrEmpty(initError)) initError = "WebView2 运行时不可用";
                return false;
            }

            Configure(core);
            Ready = true;
            NavigateHome();
            return true;
        }

        private void Configure(CoreWebView2 core)
        {
            if (configured) return;
            configured = true;

            try
            {
                core.Settings.AreDefaultContextMenusEnabled = false;
                core.Settings.IsStatusBarEnabled = false;
                core.Settings.IsSwipeNavigationEnabled = false;
                core.Settings.IsZoomControlEnabled = true;
            }
            catch { }

            core.AddWebResourceRequestedFilter(Pages.Origin + "/*", CoreWebView2WebResourceContext.All);
            core.WebResourceRequested += (s, e) => OnWebResourceRequested(core, e);
            core.PermissionRequested += (s, e) =>
            {
                // 摄像头/麦克风/剪贴板等授权，页面完全离线运行
                e.State = CoreWebView2PermissionState.Allow;
            };
            core.NavigationStarting += (s, e) =>
            {
                if (!IsAllowed(e.Uri)) e.Cancel = true;
            };
            core.NewWindowRequested += (s, e) =>
            {
                e.Handled = true;
                if (IsAllowed(e.Uri)) Navigate(e.Uri);
            };
            core.DownloadStarting += OnDownloadStarting;
            core.ProcessFailed += (s, e) =>
            {
                initError = e.ToString();
                Ready = false;
            };
        }

        private static bool IsAllowed(string uri)
        {
            if (string.IsNullOrEmpty(uri)) return false;
            return uri.StartsWith(Pages.Origin, StringComparison.OrdinalIgnoreCase)
                || uri.StartsWith("about:", StringComparison.OrdinalIgnoreCase)
                || uri.StartsWith("data:", StringComparison.OrdinalIgnoreCase);
        }

        private void OnWebResourceRequested(CoreWebView2 core, CoreWebView2WebResourceRequestedEventArgs e)
        {
            try
            {
                var uri = new Uri(e.Request.Uri);
                var path = Uri.UnescapeDataString(uri.AbsolutePath.TrimStart('/'));
                if (path.Length == 0) path = "index.html";

                Stream stream = null;
                var status = 200;
                var reason = "OK";

                if (path == "index.html" || path == fileName)
                {
                    stream = Pages.Open(resourceName);
                }

                if (stream == null)
                {
                    status = 404;
                    reason = "Not Found";
                    stream = new MemoryStream(System.Text.Encoding.UTF8.GetBytes("404 Not Found"));
                }

                var headers = status == 200
                    ? "Content-Type: text/html; charset=utf-8\r\nCache-Control: no-store"
                    : "Content-Type: text/plain; charset=utf-8";
                e.Response = core.Environment.CreateWebResourceResponse(stream, status, reason, headers);
            }
            catch { }
        }

        private void OnDownloadStarting(object sender, CoreWebView2DownloadStartingEventArgs e)
        {
            try
            {
                var dir = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
                Directory.CreateDirectory(dir);

                var name = Path.GetFileName(e.ResultFilePath);
                if (string.IsNullOrWhiteSpace(name)) name = "AirScanQR-file";
                foreach (var c in Path.GetInvalidFileNameChars()) name = name.Replace(c, '_');

                var path = Path.Combine(dir, name);
                var stem = Path.GetFileNameWithoutExtension(name);
                var ext = Path.GetExtension(name);
                var n = 1;
                while (File.Exists(path))
                    path = Path.Combine(dir, stem + " (" + (n++) + ")" + ext);

                e.ResultFilePath = path;
                LastDownloadPath = path;
                DownloadCompleted?.Invoke(path);
            }
            catch
            {
                e.Cancel = true;
            }
        }

        public event Action<string> DownloadCompleted;

        private static CoreWebView2Environment sharedEnv;

        private static async Task<CoreWebView2Environment> GetEnvironmentAsync()
        {
            if (sharedEnv != null) return sharedEnv;
            try
            {
                var userData = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "AirScanQR", "WebView2");
                Directory.CreateDirectory(userData);
                sharedEnv = await CoreWebView2Environment.CreateAsync(null, userData, null);
            }
            catch
            {
                sharedEnv = null;
            }
            return sharedEnv;
        }

        public void NavigateHome() => Navigate(Pages.Origin + "/");

        public async Task<string> EvalAsync(string script)
        {
            try
            {
                return webView.CoreWebView2 == null ? null : await webView.CoreWebView2.ExecuteScriptAsync(script);
            }
            catch
            {
                return null;
            }
        }

        private void Navigate(string url)
        {
            try { webView.CoreWebView2?.Navigate(url); } catch { }
        }

        public void Reload()
        {
            if (!Ready) return;
            try { webView.Reload(); } catch { NavigateHome(); }
        }
    }
}
